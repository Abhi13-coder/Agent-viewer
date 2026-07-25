package com.abhiram.androidtk

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Atk — talks directly to Termux's live apt repository, downloads and
 * extracts real .deb files, and runs the resulting binaries under this
 * app's own sandbox via `/system/bin/linker`, entirely bypassing the
 * Android 10+ W^X restriction that blocks direct execve() of files
 * written at runtime.
 *
 * No custom prefix, no recompiling, no proot. Termux's own binaries are
 * used unmodified; their hardcoded /data/data/com.termux/... RPATH
 * simply fails to resolve (that path doesn't exist in our sandbox), and
 * the dynamic linker falls through to LD_LIBRARY_PATH instead, which we
 * point at our own package store.
 */
class AtkPackageManager(
    private val filesDir: File,
    private val nativeLibraryDir: String,
    private val arch: String // "arm" or "aarch64" — must match Termux's repo dir naming
) {
    private val repoBase = "https://packages-cf.termux.dev/apt/termux-main"
    private val busybox = File(filesDir, "bin/busybox")
    private val pkgRoot = File(filesDir, "atk/pkg").apply { mkdirs() }     // extracted package contents live here
    private val libDir = File(filesDir, "atk/lib").apply { mkdirs() }      // flattened .so search path for LD_LIBRARY_PATH
    private val binDir = File(filesDir, "atk/bin").apply { mkdirs() }      // flattened executables
    private val cacheDir = File(filesDir, "atk/cache").apply { mkdirs() }  // downloaded .deb files
    private val indexFile = File(filesDir, "atk/Packages")
    private val installedDb = File(filesDir, "atk/installed.txt")

    data class TermuxPackage(
        val name: String,
        val version: String,
        val filename: String,   // relative path from repoBase, e.g. pool/main/c/curl/curl_8.9.1_aarch64.deb
        val sha256: String?,
        val depends: List<String>
    )

    // ---------------------------------------------------------------
    // 1. Fetch + parse Termux's live Packages.gz index
    // ---------------------------------------------------------------

    /** Downloads and gunzips the current index, caching it to disk. Call refreshIndex() to force a re-fetch. */
    fun refreshIndex(): List<TermuxPackage> {
        val url = URL("$repoBase/dists/stable/main/binary-$arch/Packages.gz")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.inputStream.use { raw ->
            GZIPInputStream(raw).use { gz ->
                indexFile.outputStream().use { out -> gz.copyTo(out) }
            }
        }
        return parseIndex()
    }

    /** Parses whatever's currently cached on disk without hitting the network. */
    fun loadCachedIndex(): List<TermuxPackage> {
        if (!indexFile.exists()) return refreshIndex()
        return parseIndex()
    }

    private fun parseIndex(): List<TermuxPackage> {
        val text = indexFile.readText()
        val stanzas = text.split(Regex("\n\n+"))
        val packages = mutableListOf<TermuxPackage>()

        for (stanza in stanzas) {
            if (stanza.isBlank()) continue
            val fields = mutableMapOf<String, String>()
            var lastKey: String? = null

            for (line in stanza.lines()) {
                if (line.isEmpty()) continue
                if (line.startsWith(" ") || line.startsWith("\t")) {
                    // continuation of previous field (e.g. multi-line Description) — ignore content, not needed
                    continue
                }
                val idx = line.indexOf(':')
                if (idx == -1) continue
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                fields[key] = value
                lastKey = key
            }

            val name = fields["Package"] ?: continue
            val version = fields["Version"] ?: continue
            val filename = fields["Filename"] ?: continue
            val sha256 = fields["SHA256"]
            val dependsRaw = fields["Depends"] ?: ""
            val depends = parseDepends(dependsRaw)

            packages.add(TermuxPackage(name, version, filename, sha256, depends))
        }
        return packages
    }

    /** "pkgA, pkgB (>= 1.2), pkgC | pkgD" -> ["pkgA", "pkgB", "pkgC"] (first alternative only, versions stripped) */
    private fun parseDepends(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(",").mapNotNull { entry ->
            val firstAlt = entry.split("|").first()
            val name = firstAlt.substringBefore("(").trim()
            name.ifBlank { null }
        }
    }

    // ---------------------------------------------------------------
    // 2. Recursive dependency resolution
    // ---------------------------------------------------------------

    fun resolveTransitive(name: String, index: List<TermuxPackage>): List<TermuxPackage> {
        val byName = index.associateBy { it.name }
        val resolved = LinkedHashMap<String, TermuxPackage>() // preserves install order, de-dupes
        val visiting = mutableSetOf<String>()

        fun visit(pkgName: String) {
            if (resolved.containsKey(pkgName) || pkgName in visiting) return
            val pkg = byName[pkgName] ?: return // dependency not in index — skip, don't hard-fail the whole install
            visiting.add(pkgName)
            for (dep in pkg.depends) visit(dep)
            visiting.remove(pkgName)
            resolved[pkgName] = pkg
        }

        visit(name)
        return resolved.values.toList()
    }

    // ---------------------------------------------------------------
    // 3. Download + extract .deb via bundled busybox (ar, tar, unxz)
    // ---------------------------------------------------------------

    fun downloadDeb(pkg: TermuxPackage, onProgress: ((String) -> Unit)? = null): File {
        val dest = File(cacheDir, File(pkg.filename).name)
        if (dest.exists() && pkg.sha256 != null && sha256Of(dest) == pkg.sha256) {
            onProgress?.invoke("${pkg.name}: already cached")
            return dest
        }

        onProgress?.invoke("${pkg.name}: downloading ${pkg.version}")
        val url = URL("$repoBase/${pkg.filename}")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.inputStream.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }

        if (pkg.sha256 != null) {
            val actual = sha256Of(dest)
            if (actual != pkg.sha256) {
                dest.delete()
                throw IllegalStateException("${pkg.name}: SHA256 mismatch (expected ${pkg.sha256}, got $actual)")
            }
        }
        return dest
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Extracts a .deb (ar archive of debian-binary + control.tar.xz + data.tar.xz) into pkgRoot/<name>/ */
    fun extractDeb(pkg: TermuxPackage, debFile: File): File {
        val extractDir = File(pkgRoot, pkg.name).apply {
            deleteRecursively()
            mkdirs()
        }

        // Step 1: ar -x pulls out debian-binary / control.tar.* / data.tar.*
        runBusybox(listOf("ar", "-x", debFile.absolutePath), workingDir = extractDir)

        // Step 2: locate data.tar.* (compression varies: .xz is Termux's current default)
        val dataTar = extractDir.listFiles()?.firstOrNull { it.name.startsWith("data.tar") }
            ?: throw IllegalStateException("${pkg.name}: no data.tar.* found after ar extraction")

        val dataDir = File(extractDir, "data").apply { mkdirs() }
        when {
            dataTar.name.endsWith(".xz") -> {
                // pipe through busybox unxz, then busybox tar -x
                runBusyboxShell(
                    "'${busybox.absolutePath}' unxz -c '${dataTar.absolutePath}' | '${busybox.absolutePath}' tar -x -C '${dataDir.absolutePath}'"
                )
            }
            dataTar.name.endsWith(".gz") -> {
                runBusybox(listOf("tar", "-xzf", dataTar.absolutePath, "-C", dataDir.absolutePath))
            }
            dataTar.name.endsWith(".zst") -> {
                throw IllegalStateException(
                    "${pkg.name}: data.tar.zst — busybox in this build has no zstd support, needs a workaround"
                )
            }
            else -> {
                runBusybox(listOf("tar", "-xf", dataTar.absolutePath, "-C", dataDir.absolutePath))
            }
        }

        return dataDir
    }

    private fun runBusybox(args: List<String>, workingDir: File? = null): String {
        val cmd = mutableListOf(busybox.absolutePath).apply { addAll(args) }
        val pb = ProcessBuilder(cmd)
        workingDir?.let { pb.directory(it) }
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        if (exit != 0) throw IllegalStateException("busybox ${args.joinToString(" ")} failed ($exit): $output")
        return output
    }

    private fun runBusyboxShell(shellLine: String): String {
        val pb = ProcessBuilder(busybox.absolutePath, "sh", "-c", shellLine)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        if (exit != 0) throw IllegalStateException("shell pipeline failed ($exit): $output")
        return output
    }

    // ---------------------------------------------------------------
    // 4. Flatten extracted package contents into our own bin/ and lib/
    // ---------------------------------------------------------------

    /** Walks the extracted data dir, copying real executables into binDir and .so files into libDir. */
    fun flattenIntoStore(dataDir: File) {
        dataDir.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            when {
                f.name.endsWith(".so") || f.name.contains(".so.") -> {
                    val target = File(libDir, f.name)
                    f.copyTo(target, overwrite = true)
                    target.setExecutable(true)
                }
                f.path.contains("/bin/") || f.path.contains("/usr/bin/") -> {
                    val target = File(binDir, f.name)
                    f.copyTo(target, overwrite = true)
                    target.setExecutable(true)
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // 5. Pure-Kotlin ELF NEEDED parser — the readelf -d ... | grep NEEDED equivalent
    // ---------------------------------------------------------------

    /** Returns the list of DT_NEEDED shared library names an ELF binary declares, e.g. ["libc.so", "libtalloc.so.2"] */
    fun neededLibraries(elfFile: File): List<String> {
        RandomAccessFile(elfFile, "r").use { raf ->
            val ident = ByteArray(16)
            raf.readFully(ident)
            require(ident[0] == 0x7f.toByte() && ident[1] == 'E'.code.toByte() &&
                    ident[2] == 'L'.code.toByte() && ident[3] == 'F'.code.toByte()) {
                "${elfFile.name}: not an ELF file"
            }
            val is64 = ident[4].toInt() == 2 // EI_CLASS: 1=32-bit, 2=64-bit
            val littleEndian = ident[5].toInt() == 1 // EI_DATA: 1=LE, 2=BE

            fun readU16(): Int = readIntLE(raf, 2, littleEndian)
            fun readU32(): Long = readIntLE(raf, 4, littleEndian).toLong() and 0xFFFFFFFFL
            fun readU64(): Long {
                val lo = readIntLE(raf, 4, littleEndian).toLong() and 0xFFFFFFFFL
                val hi = readIntLE(raf, 4, littleEndian).toLong() and 0xFFFFFFFFL
                return if (littleEndian) (hi shl 32) or lo else (lo shl 32) or hi
            }

            // skip e_type, e_machine, e_version
            raf.seek(16); readU16(); readU16(); readU32()

            val phOff: Long
            if (is64) {
                phOff = readU64()
                raf.seek(raf.filePointer + 8) // skip e_shoff
            } else {
                phOff = readU32()
                raf.seek(raf.filePointer + 4) // skip e_shoff
            }
            raf.seek(if (is64) 54 else 42) // e_phentsize offset (fixed by spec)
            val phEntSize = readU16()
            val phNum = readU16()

            data class Segment(val type: Long, val offset: Long, val vaddr: Long, val fileSz: Long)
            val segments = mutableListOf<Segment>()

            for (i in 0 until phNum) {
                raf.seek(phOff + i * phEntSize)
                if (is64) {
                    val type = readU32()
                    readU32() // flags
                    val offset = readU64()
                    val vaddr = readU64()
                    readU64() // paddr
                    val fileSz = readU64()
                    segments.add(Segment(type, offset, vaddr, fileSz))
                } else {
                    val type = readU32()
                    val offset = readU32()
                    val vaddr = readU32()
                    readU32() // paddr
                    val fileSz = readU32()
                    segments.add(Segment(type, offset, vaddr, fileSz))
                }
            }

            val dynamicSeg = segments.firstOrNull { it.type == 2L } ?: return emptyList() // PT_DYNAMIC
            val loadSegs = segments.filter { it.type == 1L } // PT_LOAD, for vaddr->offset translation

            fun vaddrToOffset(vaddr: Long): Long {
                val seg = loadSegs.firstOrNull { vaddr >= it.vaddr && vaddr < it.vaddr + it.fileSz }
                    ?: return vaddr // best-effort fallback
                return seg.offset + (vaddr - seg.vaddr)
            }

            // parse .dynamic entries: (tag, val) pairs until DT_NULL(0)
            data class DynEntry(val tag: Long, val value: Long)
            val dynEntries = mutableListOf<DynEntry>()
            var pos = dynamicSeg.offset
            val entrySize = if (is64) 16 else 8
            while (true) {
                raf.seek(pos)
                val tag = if (is64) readU64() else readU32()
                val value = if (is64) readU64() else readU32()
                if (tag == 0L) break
                dynEntries.add(DynEntry(tag, value))
                pos += entrySize
            }

            val strtabVaddr = dynEntries.firstOrNull { it.tag == 5L }?.value ?: return emptyList() // DT_STRTAB
            val strtabOffset = vaddrToOffset(strtabVaddr)
            val neededOffsets = dynEntries.filter { it.tag == 1L }.map { it.value } // DT_NEEDED

            return neededOffsets.map { relOffset ->
                raf.seek(strtabOffset + relOffset)
                val bytes = mutableListOf<Byte>()
                while (true) {
                    val b = raf.readByte()
                    if (b == 0.toByte()) break
                    bytes.add(b)
                }
                String(bytes.toByteArray(), Charsets.US_ASCII)
            }
        }
    }

    private fun readIntLE(raf: RandomAccessFile, numBytes: Int, littleEndian: Boolean): Int {
        val bytes = ByteArray(numBytes)
        raf.readFully(bytes)
        var result = 0
        val order = if (littleEndian) bytes.indices else bytes.indices.reversed()
        for (i in order) {
            result = (result shl 8) or (bytes[i].toInt() and 0xFF)
        }
        return result
    }

    // ---------------------------------------------------------------
    // 6. Run a binary via the system linker trick
    // ---------------------------------------------------------------

    /**
     * Executes an extracted binary via /system/bin/linker(64), with LD_LIBRARY_PATH
     * pointed at our own flattened lib store. This is what bypasses the W^X
     * restriction — we never execve() the downloaded file directly, we hand it
     * to the OS's own already-trusted, already-executable linker binary.
     */
    fun runViaLinker(binaryPath: String, args: List<String> = emptyList()): Process {
        val is64 = arch == "aarch64"
        val linkerPath = if (is64) "/system/bin/linker64" else "/system/bin/linker"

        val cmd = mutableListOf(linkerPath, binaryPath).apply { addAll(args) }
        val pb = ProcessBuilder(cmd)
        pb.environment()["LD_LIBRARY_PATH"] =
            "${libDir.absolutePath}:$nativeLibraryDir"
        pb.environment()["HOME"] = filesDir.absolutePath
        pb.environment()["PATH"] = "${binDir.absolutePath}:${filesDir.absolutePath}/bin:/system/bin"
        pb.redirectErrorStream(true)
        return pb.start()
    }

    // ---------------------------------------------------------------
    // 7. Top-level install command — wires steps 1-6 together
    // ---------------------------------------------------------------

    fun install(name: String, onProgress: (String) -> Unit = {}) {
        onProgress("Fetching package index...")
        val index = loadCachedIndex().ifEmpty { refreshIndex() }

        onProgress("Resolving dependencies for $name...")
        val toInstall = resolveTransitive(name, index)
        if (toInstall.none { it.name == name }) {
            throw IllegalArgumentException("Package '$name' not found in Termux index")
        }
        onProgress("Will install: ${toInstall.joinToString(", ") { it.name }}")

        for (pkg in toInstall) {
            val deb = downloadDeb(pkg, onProgress)
            onProgress("${pkg.name}: extracting...")
            val dataDir = extractDeb(pkg, deb)
            flattenIntoStore(dataDir)

            // verify: log what the actual ELF NEEDED list looks like, for anything we just installed
            dataDir.walkTopDown().filter { it.isFile && it.path.contains("/bin/") }.forEach { bin ->
                try {
                    val needed = neededLibraries(bin)
                    if (needed.isNotEmpty()) onProgress("${bin.name} needs: ${needed.joinToString(", ")}")
                } catch (e: Exception) {
                    // not an ELF, or stripped/odd format — non-fatal, just skip the check
                }
            }

            markInstalled(pkg.name, pkg.version)
            onProgress("${pkg.name} ${pkg.version} installed")
        }
    }

    private fun markInstalled(name: String, version: String) {
        installedDb.appendText("$name\t$version\n")
    }

    fun isInstalled(name: String): Boolean =
        installedDb.exists() && installedDb.readLines().any { it.startsWith("$name\t") }
}
