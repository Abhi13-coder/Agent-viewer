"""
androidtk — a Tkinter-shaped API that draws real, native, floating Android
windows instead of pixels on a canvas.

Pure standard library, no compiled dependencies, so it runs fine under
Termux Python.

Usage mirrors Tkinter on purpose:

    import androidtk as tk

    root = tk.Tk("My App")
    label = tk.Label(root, text="Hello from Python")
    label.pack()

    def on_click():
        label.config(text="Clicked!")

    button = tk.Button(root, text="Click me", command=on_click)
    button.pack()

    root.mainloop()

Requires the AndroidTk app to be running with the overlay permission
granted (tap "Grant overlay" once in the app).
"""

import json
import socket
import threading
import queue

SOCKET_NAME = "androidtk.sock"  # matches OverlayService.SOCKET_NAME


class _Connection:
    """One shared socket connection to the OverlayService, reused by every
    Tk()/Label()/Button() instance in the process."""

    _instance = None
    _lock = threading.Lock()

    def __init__(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        # Leading NUL byte selects Android's abstract socket namespace —
        # this is what LocalServerSocket("androidtk.sock") binds to on the
        # Kotlin side. No filesystem path, no permissions to worry about.
        self.sock.connect("\0" + SOCKET_NAME)
        self._file = self.sock.makefile("r")
        self.events = queue.Queue()
        self._next_id = 0
        self._id_lock = threading.Lock()
        self._reader_thread = threading.Thread(target=self._read_loop, daemon=True)
        self._reader_thread.start()

    @classmethod
    def get(cls):
        with cls._lock:
            if cls._instance is None:
                cls._instance = _Connection()
            return cls._instance

    def next_id(self):
        with self._id_lock:
            self._next_id += 1
            return self._next_id

    def send(self, obj):
        line = json.dumps(obj) + "\n"
        self.sock.sendall(line.encode("utf-8"))

    def _read_loop(self):
        while True:
            line = self._file.readline()
            if not line:
                break
            line = line.strip()
            if not line:
                continue
            try:
                self.events.put(json.loads(line))
            except json.JSONDecodeError:
                continue


class Tk:
    """A top-level floating window."""

    def __init__(self, title="Window", width=400, height=300):
        self._conn = _Connection.get()
        self.win_id = self._conn.next_id()
        self._callbacks = {}
        self._text_values = {}
        self._closed = False
        self._conn.send({
            "cmd": "create_window",
            "win_id": self.win_id,
            "title": title,
            "width": width,
            "height": height,
        })

    def title(self, text):
        # Not yet wired on the Kotlin side in this version — reserved for
        # a future set_title command.
        pass

    def mainloop(self):
        """Blocks, dispatching click/text-change events to registered
        callbacks — same shape as Tkinter's mainloop()."""
        while not self._closed:
            event = self._conn.events.get()
            self._dispatch(event)

    def _dispatch(self, event):
        kind = event.get("event")
        if kind == "click":
            cb = self._callbacks.get(event.get("widget_id"))
            if cb:
                cb()
        elif kind == "text_changed":
            self._text_values[event.get("widget_id")] = event.get("text", "")
        elif kind == "window_closed" and event.get("win_id") == self.win_id:
            self._closed = True

    def destroy(self):
        self._conn.send({"cmd": "destroy_window", "win_id": self.win_id})
        self._closed = True


class _Widget:
    _type = "label"

    def __init__(self, parent, text=""):
        self.parent = parent
        self._conn = parent._conn
        self.widget_id = self._conn.next_id()
        self._conn.send({
            "cmd": "add_widget",
            "win_id": parent.win_id,
            "widget_id": self.widget_id,
            "type": self._type,
            "text": text,
        })

    def pack(self, **kwargs):
        # Widgets are auto-stacked top-to-bottom by the overlay's content
        # LinearLayout — this version doesn't yet support pack()'s side/
        # fill/expand options, so pack() is intentionally a no-op that
        # just matches the Tkinter calling convention.
        return self

    def config(self, text=None, **kwargs):
        if text is not None:
            self._conn.send({
                "cmd": "set_text",
                "widget_id": self.widget_id,
                "text": text,
            })


class Label(_Widget):
    _type = "label"


class Button(_Widget):
    _type = "button"

    def __init__(self, parent, text="", command=None):
        super().__init__(parent, text=text)
        if command is not None:
            parent._callbacks[self.widget_id] = command


class Entry(_Widget):
    _type = "entry"

    def get(self):
        return self.parent._text_values.get(self.widget_id, "")
