# Window-level access to a DHU process: capture its window and tap inside it.
# This is the fallback for a DHU that dhu.ps1 did not start and so has no
# console to send commands to. Dot-source it; do not run it.
#
# Both calls address the DHU window only. Nothing else on the desktop is
# captured, the mouse pointer does not move and the window is not raised.

Add-Type -AssemblyName System.Drawing
if (-not ('SierraDhuWindow' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class SierraDhuWindow {
    [StructLayout(LayoutKind.Sequential)] struct RECT { public int L, T, R, B; }
    [StructLayout(LayoutKind.Sequential)] struct POINT { public int X, Y; }
    [DllImport("user32.dll")] static extern bool GetClientRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] static extern bool ClientToScreen(IntPtr h, ref POINT p);
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
    [DllImport("user32.dll")] static extern bool PostMessage(IntPtr h, uint msg, IntPtr w, IntPtr l);

    // Width and height of the window's client area.
    public static int[] ClientSize(IntPtr h) {
        RECT r; GetClientRect(h, out r);
        return new[] { r.R - r.L, r.B - r.T };
    }

    // Screen position of the client area's top-left corner.
    public static int[] ClientOrigin(IntPtr h) {
        var p = new POINT();
        ClientToScreen(h, ref p);
        return new[] { p.X, p.Y };
    }

    // Taps a point in the window by posting mouse messages to it. Move, down
    // and up are posted back to back so they are queued ahead of the
    // WM_MOUSELEAVE that SDL asks for, which would otherwise reset the pointer
    // position to the real cursor before the button is read.
    public static void Tap(IntPtr h, int x, int y) {
        IntPtr l = (IntPtr)((y << 16) | (x & 0xFFFF));
        PostMessage(h, 0x0200, IntPtr.Zero, l);   // WM_MOUSEMOVE
        PostMessage(h, 0x0201, (IntPtr)1, l);     // WM_LBUTTONDOWN
        PostMessage(h, 0x0202, IntPtr.Zero, l);   // WM_LBUTTONUP
    }
}
'@
}

function Get-DhuWindow {
    param([int]$ProcessId)
    $procs = if ($ProcessId) { Get-Process -Id $ProcessId -ErrorAction SilentlyContinue } else { Get-Process desktop-head-unit -ErrorAction SilentlyContinue }
    $p = @($procs | Where-Object { $_.MainWindowHandle -ne 0 }) | Select-Object -First 1
    if (-not $p) { throw "No Desktop Head Unit window is open." }
    return $p
}

function Test-BitmapBlank {
    param([System.Drawing.Bitmap]$Bitmap)
    $first = $Bitmap.GetPixel(0, 0).ToArgb()
    for ($y = 0; $y -lt $Bitmap.Height; $y += 16) {
        for ($x = 0; $x -lt $Bitmap.Width; $x += 16) {
            if ($Bitmap.GetPixel($x, $y).ToArgb() -ne $first) { return $false }
        }
    }
    return $true
}

# Saves the DHU window's client area as a PNG. Returns 'printwindow' when the
# window drew itself into the bitmap (this works when other windows cover it),
# or 'screen' when that came back blank and the client rectangle was copied
# from the screen instead (the window must then be visible). Only the DHU's own
# rectangle is ever read.
function Save-DhuWindow {
    param([Parameter(Mandatory)][IntPtr]$Handle, [Parameter(Mandatory)][string]$Path)
    if ([SierraDhuWindow]::IsIconic($Handle)) { throw "The DHU window is minimised; restore it first." }
    $size = [SierraDhuWindow]::ClientSize($Handle)
    if ($size[0] -le 0 -or $size[1] -le 0) { throw "The DHU window has no client area." }
    $bitmap = [System.Drawing.Bitmap]::new($size[0], $size[1])
    try {
        $how = 'printwindow'
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        try {
            $hdc = $graphics.GetHdc()
            # 1 = PW_CLIENTONLY, 2 = PW_RENDERFULLCONTENT
            $ok = [SierraDhuWindow]::PrintWindow($Handle, $hdc, 3)
            $graphics.ReleaseHdc($hdc)
            if (-not $ok -or (Test-BitmapBlank $bitmap)) {
                $origin = [SierraDhuWindow]::ClientOrigin($Handle)
                $graphics.CopyFromScreen($origin[0], $origin[1], 0, 0, $bitmap.Size)
                $how = 'screen'
            }
        } finally { $graphics.Dispose() }
        $bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
        return $how
    } finally { $bitmap.Dispose() }
}
