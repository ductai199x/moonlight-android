// PrivacyScreen: blanks every physical monitor of this computer while leaving
// the remote-desktop stream (Sunshine/Moonlight) untouched.
//
// A full-screen black window is placed on each monitor and flagged with
// WDA_EXCLUDEFROMCAPTURE, so screen capture skips it: the physical displays
// show black, the captured desktop is unchanged. The windows are click-through
// so injected input still reaches the real desktop.
//
// Control:
//   Ctrl+Alt+Shift+P            toggle (global hotkey; send it from the Moonlight modifier bar)
//   PrivacyScreen.exe --on|--off|--toggle   control a running instance
//   tray icon                   toggle / exit
//
// Build: build.ps1 (uses the C# compiler that ships with Windows).

using System;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

static class Log
{
    static readonly string path = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "PrivacyScreen.log");
    public static void Write(string msg)
    {
        try { System.IO.File.AppendAllText(path, DateTime.Now.ToString("HH:mm:ss.fff ") + msg + Environment.NewLine); } catch { }
    }
}

static class Native
{
    public const int WDA_EXCLUDEFROMCAPTURE = 0x11;
    public const int WS_EX_TRANSPARENT = 0x20, WS_EX_TOOLWINDOW = 0x80, WS_EX_LAYERED = 0x80000, WS_EX_NOACTIVATE = 0x08000000;
    public const int LWA_ALPHA = 2;
    public static readonly IntPtr HWND_TOPMOST = new IntPtr(-1);
    public const uint SWP_NOMOVE = 2, SWP_NOSIZE = 1, SWP_NOACTIVATE = 0x10, SWP_SHOWWINDOW = 0x40;
    public const int WM_HOTKEY = 0x312;
    public const uint MOD_ALT = 1, MOD_CONTROL = 2, MOD_SHIFT = 4, MOD_NOREPEAT = 0x4000;

    [DllImport("user32.dll")] public static extern bool SetWindowDisplayAffinity(IntPtr hWnd, uint affinity);
    [DllImport("user32.dll")] public static extern bool SetLayeredWindowAttributes(IntPtr hWnd, uint key, byte alpha, uint flags);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
    [DllImport("user32.dll")] public static extern bool RegisterHotKey(IntPtr hWnd, int id, uint mods, uint vk);
    [DllImport("user32.dll")] public static extern bool UnregisterHotKey(IntPtr hWnd, int id);
    [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr ctx);
}

class Overlay : Form
{
    readonly bool dot;
    public bool CaptureExcluded;

    public Overlay(Rectangle bounds, bool dot)
    {
        this.dot = dot;
        FormBorderStyle = FormBorderStyle.None;
        ShowInTaskbar = false;
        StartPosition = FormStartPosition.Manual;
        BackColor = dot ? Color.FromArgb(0, 200, 120) : Color.Black;
        TopMost = true;
        Bounds = bounds;
    }

    protected override bool ShowWithoutActivation { get { return true; } }

    protected override CreateParams CreateParams
    {
        get
        {
            var cp = base.CreateParams;
            cp.ExStyle |= Native.WS_EX_LAYERED | Native.WS_EX_TRANSPARENT | Native.WS_EX_TOOLWINDOW | Native.WS_EX_NOACTIVATE;
            return cp;
        }
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        Native.SetLayeredWindowAttributes(Handle, 0, 255, Native.LWA_ALPHA);
        // The status dot is deliberately visible in the stream, as feedback
        if (!dot) CaptureExcluded = Native.SetWindowDisplayAffinity(Handle, Native.WDA_EXCLUDEFROMCAPTURE);
    }

    public void Raise()
    {
        Native.SetWindowPos(Handle, Native.HWND_TOPMOST, 0, 0, 0, 0,
            Native.SWP_NOMOVE | Native.SWP_NOSIZE | Native.SWP_NOACTIVATE | Native.SWP_SHOWWINDOW);
    }
}

class Controller : Form
{
    const int HOTKEY_ID = 1;
    readonly System.Collections.Generic.List<Overlay> overlays = new System.Collections.Generic.List<Overlay>();
    readonly NotifyIcon tray = new NotifyIcon();
    readonly System.Windows.Forms.Timer keepOnTop = new System.Windows.Forms.Timer();
    bool active;

    public Controller(string startMode)
    {
        ShowInTaskbar = false;
        WindowState = FormWindowState.Minimized;
        Opacity = 0;

        // The form is never shown, so create its handle explicitly: the hotkey
        // and cross-thread requests both need it
        CreateHandle();

        var menu = new ContextMenuStrip();
        menu.Items.Add("Toggle privacy screen", null, (s, e) => Toggle());
        menu.Items.Add("Exit", null, (s, e) => { SetActive(false); Application.Exit(); });
        tray.ContextMenuStrip = menu;
        tray.Icon = SystemIcons.Shield;
        tray.Text = "Privacy screen";
        tray.Visible = true;
        tray.DoubleClick += (s, e) => Toggle();

        keepOnTop.Interval = 1000;
        keepOnTop.Tick += (s, e) => { foreach (var o in overlays) o.Raise(); };

        SystemEvents.DisplaySettingsChanged += (s, e) => { if (active) { Rebuild(); } };

        if (startMode == "--on") SetActive(true);
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        // Ctrl+Alt+Shift+P first; the fallbacks cover another app owning it
        Keys[] candidates = { Keys.P, Keys.F12, Keys.F11 };
        foreach (var k in candidates)
        {
            if (Native.RegisterHotKey(Handle, HOTKEY_ID,
                    Native.MOD_CONTROL | Native.MOD_ALT | Native.MOD_SHIFT | Native.MOD_NOREPEAT, (uint)k))
            {
                Log.Write("hotkey registered: Ctrl+Alt+Shift+" + k);
                return;
            }
            Log.Write("hotkey unavailable: Ctrl+Alt+Shift+" + k + " (error " + Marshal.GetLastWin32Error() + ")");
        }
        tray.ShowBalloonTip(3000, "Privacy screen", "No hotkey could be registered; use the tray menu.", ToolTipIcon.Warning);
    }

    protected override void SetVisibleCore(bool value) { base.SetVisibleCore(false); }

    protected override void WndProc(ref Message m)
    {
        if (m.Msg == Native.WM_HOTKEY && m.WParam.ToInt32() == HOTKEY_ID) Toggle();
        base.WndProc(ref m);
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        Native.UnregisterHotKey(Handle, HOTKEY_ID);
        SetActive(false);
        tray.Visible = false;
        base.OnFormClosing(e);
    }

    public void Toggle() { SetActive(!active); }

    public void SetActive(bool on)
    {
        if (on == active) return;
        active = on;
        Log.Write(on ? "privacy ON" : "privacy off");
        if (on) Rebuild(); else Clear();
        keepOnTop.Enabled = on;
        tray.Text = on ? "Privacy screen: ON" : "Privacy screen: off";
    }

    void Clear()
    {
        foreach (var o in overlays) { o.Close(); o.Dispose(); }
        overlays.Clear();
    }

    void Rebuild()
    {
        Clear();
        var screens = Screen.AllScreens;
        foreach (var s in screens)
        {
            var o = new Overlay(s.Bounds, false);
            overlays.Add(o);
            o.Show();
            o.Raise();
            Log.Write("overlay " + s.DeviceName + " " + s.Bounds + " excluded=" + o.CaptureExcluded);
        }

        // Small marker on the primary display so the remote viewer can tell
        // that privacy mode is on
        var p = Screen.PrimaryScreen.Bounds;
        var dot = new Overlay(new Rectangle(p.Right - 14, p.Top + 4, 10, 10), true);
        overlays.Add(dot);
        dot.Show();
        dot.Raise();

        foreach (var o in overlays)
            if (!o.CaptureExcluded && o.BackColor == Color.Black)
                tray.ShowBalloonTip(3000, "Privacy screen", "Capture exclusion is not supported on this Windows version; the stream would show black.", ToolTipIcon.Error);
    }
}

static class Program
{
    [STAThread]
    static void Main(string[] args)
    {
        // Per-monitor v2 DPI awareness so monitor bounds are real pixels
        try { Native.SetProcessDpiAwarenessContext(new IntPtr(-4)); } catch { }

        string mode = args.Length > 0 ? args[0].ToLowerInvariant() : "";

        // --on --for N: switch off again after N seconds (safety net for tests)
        int autoOffSeconds = 0;
        for (int a = 1; a + 1 < args.Length; a++)
            if (args[a] == "--for") int.TryParse(args[a + 1], out autoOffSeconds);

        bool created;
        using (var mutex = new Mutex(true, "PrivacyScreen.SingleInstance", out created))
        using (var evOn = new EventWaitHandle(false, EventResetMode.AutoReset, "PrivacyScreen.On"))
        using (var evOff = new EventWaitHandle(false, EventResetMode.AutoReset, "PrivacyScreen.Off"))
        using (var evToggle = new EventWaitHandle(false, EventResetMode.AutoReset, "PrivacyScreen.Toggle"))
        {
            if (!created)
            {
                // Forward the request to the running instance
                if (mode == "--on") evOn.Set();
                else if (mode == "--off") evOff.Set();
                else evToggle.Set();
                if (mode == "--on" && autoOffSeconds > 0)
                {
                    Thread.Sleep(autoOffSeconds * 1000);
                    evOff.Set();
                }
                return;
            }

            Application.EnableVisualStyles();
            var controller = new Controller(mode);
            if (mode == "--on" && autoOffSeconds > 0)
            {
                var off = new System.Windows.Forms.Timer { Interval = autoOffSeconds * 1000 };
                off.Tick += (s, e) => { off.Stop(); controller.SetActive(false); };
                off.Start();
            }

            var listener = new Thread(() =>
            {
                var handles = new WaitHandle[] { evOn, evOff, evToggle };
                while (true)
                {
                    int i = WaitHandle.WaitAny(handles);
                    try
                    {
                        controller.BeginInvoke((MethodInvoker)(() =>
                        {
                            if (i == 0) controller.SetActive(true);
                            else if (i == 1) controller.SetActive(false);
                            else controller.Toggle();
                        }));
                    }
                    catch (Exception ex) { Log.Write("request failed: " + ex.Message); }
                }
            });
            listener.IsBackground = true;
            listener.Start();

            Application.Run(controller);
        }
    }
}
