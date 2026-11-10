# Generates an Adafruit-GFX digits font ('-'..':') for TFT_eSPI from an installed
# Windows font. Digits share one advance (tabular); chars in -PropChars get a
# tight advance of their own ink width (e.g. ".").
#   .\tools\mkfont.ps1 -Out include\SpeedDigits.h -EmPx 70
#   .\tools\mkfont.ps1 -Out include\ValueDigits.h -EmPx 32 -Name ValueDigits -PropChars ".:"
#   .\tools\mkfont.ps1 -Out include\BigDigits.h -EmPx 56 -Name BigDigits -PropChars ".:"
#   .\tools\mkfont.ps1 -Out include\ClockDigits.h -EmPx 110 -Family "Arial Black" -Style Regular -Track 0.03 -Name ClockDigits -PropChars ".:"
param([string]$Out, [float]$EmPx, [string]$Family = "Arial", [string]$Name = "SpeedDigits", [string]$PropChars = "", [string]$Style = "Bold", [float]$Track = 0.07)
Add-Type -AssemblyName System.Drawing

$first = 0x2D; $last = 0x3A                    # '-' .. ':'
$font = New-Object System.Drawing.Font($Family, $EmPx, [System.Drawing.FontStyle]::$Style, [System.Drawing.GraphicsUnit]::Pixel)
$ff = $font.FontFamily
$ascentPx = $EmPx * $ff.GetCellAscent([System.Drawing.FontStyle]::$Style) / $ff.GetEmHeight([System.Drawing.FontStyle]::$Style)
$fmt = [System.Drawing.StringFormat]::GenericTypographic
$ox = 20; $oy = 20
$glyphs = @(); $bits = New-Object System.Collections.Generic.List[byte]
$maxW = 0; $digitTop = 999; $digitBot = -1

$raw = @()
for ($c = $first; $c -le $last; $c++) {
    $bmp = New-Object System.Drawing.Bitmap(400, 400)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.Clear([System.Drawing.Color]::Black)
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $g.DrawString([string][char]$c, $font, [System.Drawing.Brushes]::White, $ox, $oy, $fmt)
    $g.Dispose()
    $x0 = 999; $y0 = 999; $x1 = -1; $y1 = -1
    for ($y = 0; $y -lt 400; $y++) { for ($x = 0; $x -lt 400; $x++) {
        if ($bmp.GetPixel($x, $y).R -ge 128) { if ($x -lt $x0) {$x0=$x}; if ($x -gt $x1) {$x1=$x}; if ($y -lt $y0) {$y0=$y}; if ($y -gt $y1) {$y1=$y} }
    } }
    $raw += ,@($c, $bmp, $x0, $y0, $x1, $y1)
    if ($x1 -ge 0 -and $c -ge 0x30 -and $c -le 0x39) { $maxW = [Math]::Max($maxW, $x1 - $x0 + 1); $digitTop = [Math]::Min($digitTop, $y0); $digitBot = [Math]::Max($digitBot, $y1) }
}
$track = [int][Math]::Round($EmPx * $Track)
$adv = $maxW + $track                              # tabular digits, tight tracking
$baseline = $oy + [int][Math]::Round($ascentPx)

foreach ($r in $raw) {
    $c, $bmp, $x0, $y0, $x1, $y1 = $r
    $off = $bits.Count
    if ($x1 -lt 0) { $glyphs += "  { $off, 0, 0, $adv, 0, 0 }"; continue }
    $w = $x1 - $x0 + 1; $h = $y1 - $y0 + 1
    $acc = 0; $n = 0
    for ($y = $y0; $y -le $y1; $y++) { for ($x = $x0; $x -le $x1; $x++) {
        $acc = ($acc -shl 1) -bor [int]($bmp.GetPixel($x, $y).R -ge 128); $n++
        if ($n -eq 8) { $bits.Add([byte]$acc); $acc = 0; $n = 0 }
    } }
    if ($n -gt 0) { $bits.Add([byte](($acc -shl (8 - $n)) -band 0xFF)) }
    if ($PropChars.Contains([string][char]$c)) {
        $a = $w + $track                           # proportional: own ink width
    } else {
        $a = $adv                                  # tabular
    }
    # centre each glyph's ink in its advance
    $xo = [int][Math]::Floor(($a - $w) / 2)
    $glyphs += "  { $off, $w, $h, $a, $xo, $($y0 - $baseline) }"
}

$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("// $Name.h - generated: $Family $Style, ${EmPx} px em, chars '-'..':' only.")
[void]$sb.AppendLine("// Adafruit-GFX font for TFT_eSPI setFreeFont(). Digits are tabular, $adv px advance,")
[void]$sb.AppendLine("// ink $($digitBot - $digitTop + 1) px tall. Regenerate rather than hand-edit.")
[void]$sb.AppendLine("#pragma once")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("const uint8_t ${Name}Bitmaps[] PROGMEM = {")
for ($i = 0; $i -lt $bits.Count; $i += 16) {
    $row = ($bits[$i..([Math]::Min($i + 15, $bits.Count - 1))] | ForEach-Object { '0x{0:X2}' -f $_ }) -join ', '
    [void]$sb.AppendLine("  $row,")
}
[void]$sb.AppendLine("};")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("const GFXglyph ${Name}Glyphs[] PROGMEM = {")
[void]$sb.AppendLine(($glyphs -join ",`n"))
[void]$sb.AppendLine("};")
[void]$sb.AppendLine("")
[void]$sb.AppendLine("const GFXfont $Name PROGMEM = { (uint8_t *)${Name}Bitmaps, (GFXglyph *)${Name}Glyphs, 0x$('{0:X2}' -f $first), 0x$('{0:X2}' -f $last), $([int][Math]::Round($EmPx * 1.15)) };")
[System.IO.File]::WriteAllText($Out, $sb.ToString())
"advance=$adv  digit ink height=$($digitBot - $digitTop + 1)  3-digit width=$(3 * $adv)  bytes=$($bits.Count)"
