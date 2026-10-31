@echo off
setlocal DisableDelayedExpansion
set "TRIM_VIDEOS_SELF=%~f0"
set "TRIM_VIDEOS_CWD=%CD%"
set "TRIM_VIDEOS_TEMP=%TEMP%\trim-videos-%RANDOM%-%RANDOM%-%RANDOM%.ps1"
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $text=[IO.File]::ReadAllText($env:TRIM_VIDEOS_SELF,[Text.Encoding]::UTF8); $marker=('#'+'__TRIM_POWERSHELL__'); $offset=$text.LastIndexOf($marker,[StringComparison]::Ordinal); if($offset -lt 0){throw 'Missing script payload'}; $stream=[IO.File]::Open($env:TRIM_VIDEOS_TEMP,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None); $writer=[IO.StreamWriter]::new($stream,[Text.UTF8Encoding]::new($true)); try{$writer.Write($text.Substring($offset+$marker.Length))}finally{$writer.Dispose()}"
if errorlevel 1 goto trim_extract_failed
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%TRIM_VIDEOS_TEMP%" %*
set "trimExitCode=%ERRORLEVEL%"
powershell.exe -NoProfile -Command "[IO.File]::Delete($env:TRIM_VIDEOS_TEMP)"
goto trim_done
:trim_extract_failed
set "trimExitCode=1"
:trim_done
echo.
pause
exit /b %trimExitCode%
#__TRIM_POWERSHELL__
#requires -Version 5.1
<#
.SYNOPSIS
批量剪去当前目录视频的开头，不递归；输出到 out，保留原文件名。
.DESCRIPTION
需要 ffmpeg.exe 和 ffprobe.exe：放在脚本旁或加入 PATH，也可用参数指定。
默认 Copy：原编码流复制，向后对齐关键帧，实际可能多剪一些，会显示差额。
Exact：按视频帧精确剪切，全部保留段重新编码 H.264 / AAC，较慢且有损；
仅支持 MP4/MOV/M4V/MKV/TS/MTS/M2TS。不会自动从 Copy 切换到 Exact。
Copy 保留视频、全部音轨和字幕；Exact 对视频/音频重新编码，字幕复制。
只支持一个视频流；封面图、附件和数据流会明确拒绝，不静默丢弃。
两者删除旧章节时间。已有 out 同名项跳过，失败不会发布正式输出。
默认检查开头 8 帧能否与源对应，不做耗时的全片解码检查。
FFmpeg 定位选项：https://ffmpeg.org/ffmpeg.html#Main-options
FFprobe 区间说明：https://ffmpeg.org/ffprobe.html#Main-options
.EXAMPLE
.\trim-videos.cmd -Seconds 3
.EXAMPLE
.\trim-videos.cmd -Seconds 2.5 -Mode Exact
.EXAMPLE
.\trim-videos.cmd -Seconds 3 -Directory 'D:\视频'
#>
[CmdletBinding()]
param(
    [double]$Seconds = 3,
    [ValidateSet('Copy', 'Exact')][string]$Mode = 'Copy',
    [string]$Directory = $env:TRIM_VIDEOS_CWD,
    [string]$FFmpeg,
    [string]$FFprobe,
    [ValidateRange(1, 3600)][int]$KeyframeSearchSeconds = 120
)

$ErrorActionPreference = 'Stop'
$invariant = [Globalization.CultureInfo]::InvariantCulture
$scriptDirectory = Split-Path -Parent $env:TRIM_VIDEOS_SELF

function Format-Time([double]$Value) {
    $Value.ToString('0.000000000', $invariant)
}

function Get-Number($Value, [double]$Fallback = [double]::NaN) {
    $parsed = 0.0
    if ([double]::TryParse([string]$Value, [Globalization.NumberStyles]::Float,
            $invariant, [ref]$parsed) -and ![double]::IsNaN($parsed) -and ![double]::IsInfinity($parsed)) {
        return $parsed
    }
    return $Fallback
}

function Find-Tool([string]$Explicit, [string]$Name, [string]$Sibling) {
    if ($Explicit) {
        if (!(Test-Path -LiteralPath $Explicit -PathType Leaf)) { throw "找不到工具：$Explicit" }
        return (Resolve-Path -LiteralPath $Explicit).ProviderPath
    }
    foreach ($candidate in @((Join-Path $scriptDirectory "$Name.exe"), $Sibling)) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) { return $candidate }
    }
    $command = Get-Command "$Name.exe" -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (!$command) { throw "找不到 $Name.exe，请将它放在脚本旁、加入 PATH，或用 -$Name 指定完整路径。" }
    return $command.Source
}

function ConvertTo-NativeArgument([AllowEmptyString()][string]$Value) {
    # Windows CRT argument rules, including quotes and trailing backslashes.
    $escaped = [regex]::Replace($Value, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
    return '"' + $escaped + '"'
}

function Invoke-Tool([string]$Tool, [string[]]$ArgumentList) {
    # FFmpeg/FFprobe emit UTF-8. Bypass PowerShell 5.1's console-codepage decoder:
    # GBK can consume one of JSON's path backslashes and cause invalid escapes.
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $Tool
    $startInfo.Arguments = ($ArgumentList | ForEach-Object { ConvertTo-NativeArgument $_ }) -join ' '
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $startInfo.StandardErrorEncoding = [Text.UTF8Encoding]::new($false)
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    $started = $false
    try {
        $started = $process.Start()
        $process.StandardInput.Close()
        # Drain both pipes concurrently, including long FFprobe JSON and diagnostics.
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        while (!$process.WaitForExit(200)) { }
        $result = $stdoutTask.GetAwaiter().GetResult()
        $diagnostics = $stderrTask.GetAwaiter().GetResult()
        $code = $process.ExitCode
        if ($code -ne 0) {
            if ($diagnostics.Length -gt 4000) { $diagnostics = $diagnostics.Substring($diagnostics.Length - 4000) }
            throw "$(Split-Path -Leaf $Tool) 失败（$code）：`n$diagnostics"
        }
        return $result
    } finally {
        if ($started -and !$process.HasExited) {
            $process.Kill()
            $process.WaitForExit()
        }
        $process.Dispose()
    }
}

function Read-Probe([string]$Path, [string[]]$Options) {
    $json = Invoke-Tool $probe (@('-v', 'error') + $Options + @('-of', 'json', $Path))
    return ($json | ConvertFrom-Json)
}

function Get-FrameHashes([string]$Path, [double]$AbsoluteStart, [int]$StreamIndex) {
    $output = Invoke-Tool $encoder @('-hide_banner', '-v', 'error', '-xerror', '-nostdin',
        '-copyts', '-i', $Path, '-ss', (Format-Time $AbsoluteStart), '-map', "0:$StreamIndex",
        '-an', '-sn', '-dn', '-frames:v', '8', '-fps_mode', 'passthrough', '-f', 'framemd5', '-')
    foreach ($line in ($output -split "`n")) {
        if ($line -match '^\s*\d+\s*,') { ($line -split ',')[-1].Trim() }
    }
}

try {
    # Windows PowerShell may fall back to PSHOME when cwd contains brackets.
    Set-Location -LiteralPath $env:TRIM_VIDEOS_CWD
    if (!$PSBoundParameters.ContainsKey('Seconds')) {
        $answer = Read-Host '剪去开头多少秒？支持小数，直接回车为 3 秒'
        if ($answer.Trim()) { $Seconds = Get-Number $answer }
    }
    if ([double]::IsNaN($Seconds) -or [double]::IsInfinity($Seconds) -or $Seconds -le 0) {
        throw 'Seconds 必须是大于 0 的秒数，例如 3 或 2.5。'
    }
    $sourceDir = Get-Item -LiteralPath $Directory
    if (!$sourceDir.PSIsContainer -or $sourceDir.PSProvider.Name -ne 'FileSystem') { throw 'Directory 必须是本机文件夹。' }
    $encoder = Find-Tool $FFmpeg 'ffmpeg' ''
    $probe = Find-Tool $FFprobe 'ffprobe' (Join-Path (Split-Path -Parent $encoder) 'ffprobe.exe')
    $extensions = @('.mp4', '.mkv', '.mov', '.m4v', '.avi', '.webm', '.ts', '.mts', '.m2ts',
        '.wmv', '.flv', '.mpg', '.mpeg', '.vob', '.3gp', '.ogv')
    $files = @(Get-ChildItem -LiteralPath $sourceDir.FullName -File |
        Where-Object { $extensions -contains $_.Extension.ToLowerInvariant() } | Sort-Object Name)
    if (!$files.Count) { Write-Host '当前目录没有支持的视频文件。'; exit 0 }
    $outDir = Join-Path $sourceDir.FullName 'out'
    if (!(Test-Path -LiteralPath $outDir)) { [void][IO.Directory]::CreateDirectory($outDir) }
    if (!(Test-Path -LiteralPath $outDir -PathType Container)) { throw 'out 已存在且不是文件夹。' }
    if ((Get-Item -LiteralPath $outDir).Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw 'out 是链接或目录联接，请改用当前文件夹中的普通 out 目录。'
    }
    Write-Host "目录：$($sourceDir.FullName)；视频：$($files.Count)；模式：$Mode；指定剪去：$Seconds 秒"
    if ($Mode -eq 'Copy') { Write-Host '无损模式会向后对齐关键帧；不保证刚好是指定秒数。' }
    else { Write-Host '精确模式会重新编码保留的全部视频/音频，速度较慢，画质/音质会变化。' }

    $ok = 0; $skipped = 0; $failed = 0
    foreach ($file in $files) {
        $temporary = $null
        try {
            $destination = Join-Path $outDir $file.Name
            if (Test-Path -LiteralPath $destination) {
                Write-Host "[跳过] out 中已存在：$($file.Name)"
                $skipped++
                continue
            }
            Write-Host "[处理] $($file.Name)"
            $sourceLength = $file.Length
            $sourceTime = $file.LastWriteTimeUtc
            $info = Read-Probe $file.FullName @('-show_format', '-show_streams')
            $videos = @($info.streams | Where-Object { $_.codec_type -eq 'video' -and !$_.disposition.attached_pic })
            if ($videos.Count -ne 1) { throw '需要恰好一个非封面视频流；本文件未处理。' }
            $extra = @($info.streams | Where-Object {
                $_.codec_type -notin @('video', 'audio', 'subtitle') -or $_.disposition.attached_pic
            })
            if ($extra.Count) { throw '本文件含封面图、附件或数据流，需要单独规划，未处理。' }
            $video = $videos[0]
            $origin = Get-Number $info.format.start_time 0
            $videoStart = Get-Number $video.start_time $origin
            $duration = Get-Number $video.duration
            $end = if ([double]::IsNaN($duration)) {
                $origin + (Get-Number $info.format.duration)
            } else { $videoStart + $duration }
            $target = $origin + $Seconds
            if ([double]::IsNaN($end) -or $target -ge $end) { throw '视频时长未知或裁剪秒数已达到视频末尾。' }

            $common = @('-map_metadata', '0', '-map_chapters', '-1')
            $actual = $target
            if ($Mode -eq 'Copy') {
                # Absolute end avoids ffprobe's interval length being measured from an earlier seek point.
                $interval = "$(Format-Time ([Math]::Max($origin, $target - 2)))%$(Format-Time ([Math]::Min($end, $target + $KeyframeSearchSeconds)))"
                $packets = Read-Probe $file.FullName @('-select_streams', "$($video.index)",
                    '-read_intervals', $interval, '-show_packets', '-show_entries', 'packet=pts_time,dts_time,flags')
                $keys = @($packets.packets | Where-Object {
                    $_.flags -match 'K' -and $_.flags -notmatch 'D' -and
                    (Get-Number $_.pts_time) -ge ($target - 0.000001)
                } | Sort-Object { Get-Number $_.pts_time })
                if (!$keys.Count) {
                    throw "指定点后 $KeyframeSearchSeconds 秒内没有关键帧。可增大 -KeyframeSearchSeconds 或显式使用 -Mode Exact。"
                }
                $key = $keys[0]
                $actual = Get-Number $key.pts_time
                $dts = Get-Number $key.dts_time
                if ([double]::IsNaN($dts)) { throw '关键帧缺少 DTS，无法安全进行无损定位。' }
                # Packet K flags alone may describe open GOPs. Confirm decoder key_frame too.
                $frames = Read-Probe $file.FullName @('-select_streams', "$($video.index)",
                    '-read_intervals', $interval, '-skip_frame', 'nokey', '-show_frames',
                    '-show_entries', 'frame=key_frame,best_effort_timestamp_time')
                $matched = @($frames.frames | Where-Object {
                    $_.key_frame -eq 1 -and [Math]::Abs((Get-Number $_.best_effort_timestamp_time) - $actual) -lt 0.00001
                })
                if (!$matched.Count) { throw '关键包没有对应的解码关键帧，未导出。可显式选择 Exact 重编码。' }
                # With B frames, output-side stream-copy -ss gates on DTS, not displayed PTS.
                # A common shift retains the relative audio clock; negative DTS is valid preroll.
                $seek = $dts - 0.000001
                $copyArgs = @('-copyts', '-i', $file.FullName, '-ss', (Format-Time $seek), '-map', '0') +
                    $common + @('-c', 'copy', '-output_ts_offset', (Format-Time ($seek - $actual)),
                    '-avoid_negative_ts', 'disabled')
                $exportArgs = $copyArgs
                Write-Host ('       实际剪去 {0:0.######} 秒，多剪 {1:0.######} 秒（向后关键帧）' -f
                    ($actual - $origin), [Math]::Max(0, $actual - $target))
            } else {
                if ($file.Extension.ToLowerInvariant() -notin @('.mp4', '.mov', '.m4v', '.mkv', '.ts', '.mts', '.m2ts')) {
                    throw '该容器不支持本脚本的 Exact H.264/AAC 方案；请用 Copy，或先另存为 MP4/MKV。'
                }
                $exportArgs = @('-copyts', '-i', $file.FullName, '-ss', (Format-Time $target),
                    '-map', '0:V:0', '-map', '0:a?', '-map', '0:s?') + $common +
                    @('-c:v', 'libx264', '-preset', 'fast', '-crf', '18', '-fps_mode:v', 'passthrough',
                    '-c:a', 'aac', '-b:a', '192k', '-c:s', 'copy', '-avoid_negative_ts', 'disabled')
            }
            $temporary = Join-Path $outDir ('.trim-' + [Guid]::NewGuid().ToString('N') + $file.Extension)
            [void](Invoke-Tool $encoder (@('-hide_banner', '-nostdin', '-v', 'error', '-n') + $exportArgs + @($temporary)))
            $result = Read-Probe $temporary @('-show_format', '-show_streams')
            $outputVideo = @($result.streams | Where-Object { $_.codec_type -eq 'video' -and !$_.disposition.attached_pic })
            if ($outputVideo.Count -ne 1 -or (Get-Number $result.format.duration 0) -le 0) { throw '输出没有有效视频或时长。' }
            foreach ($type in @('video', 'audio', 'subtitle')) {
                if (@($info.streams | Where-Object { $_.codec_type -eq $type }).Count -ne
                    @($result.streams | Where-Object { $_.codec_type -eq $type }).Count) {
                    throw "输出的 $type 流数量与源文件不一致，未发布。"
                }
            }
            $outputOrigin = Get-Number $result.format.start_time 0
            $outputVideoStart = Get-Number $outputVideo[0].start_time $outputOrigin
            $outputHashes = @(Get-FrameHashes $temporary $outputVideoStart $outputVideo[0].index)
            if (!$outputHashes.Count) { throw '输出开头无法解码。' }
            if ($Mode -eq 'Copy') {
                $sourceHashes = @(Get-FrameHashes $file.FullName $actual $video.index)
                if (($sourceHashes -join ',') -ne ($outputHashes -join ',')) {
                    throw '输出开头与指定关键帧的源画面不一致，可能存在开放 GOP 或容器定位差异；未发布。请用 Exact。'
                }
            }
            $file.Refresh()
            if ($file.Length -ne $sourceLength -or $file.LastWriteTimeUtc -ne $sourceTime) { throw '处理期间源文件发生变化，未发布。' }
            # File.Move's two-argument overload never overwrites a concurrently-created destination.
            [IO.File]::Move($temporary, $destination)
            $temporary = $null
            $ok++
            Write-Host "[完成] $destination"
        } catch {
            $failed++
            Write-Host "[失败] $($file.Name)：$($_.Exception.Message)" -ForegroundColor Red
        } finally {
            if ($temporary -and (Test-Path -LiteralPath $temporary -PathType Leaf)) {
                Remove-Item -LiteralPath $temporary -ErrorAction SilentlyContinue
            }
        }
    }
    Write-Host "处理结束：成功 $ok；已存在跳过 $skipped；失败 $failed。原文件保留。"
    if ($failed) { exit 1 }
    exit 0
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
