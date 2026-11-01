param([switch]$CheckOnly, [string]$Cache = '')
$ErrorActionPreference = 'Stop'
$Distro = 'JBL-AirPlay'
$ImageName = 'ubuntu-24.04.5-wsl-amd64.wsl'
$ImageHash = 'BB415D824822C4B878125729AF451A5D18FB13D1CF5CBED9A7393AD64AC6039E'
if (-not $Cache) {
    $Cache = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../scratchpad/airplay2'))
}
$Location = Join-Path $env:LOCALAPPDATA 'JBL-AirPlay/WSL'
$Cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
$Elevated = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if ($CheckOnly) {
    [pscustomobject]@{
        FirmwareVirtualizationEnabled = [bool]$Cpu.VirtualizationFirmwareEnabled
        Elevated = $Elevated
        DistroName = $Distro
        ImageCached = Test-Path -LiteralPath (Join-Path $Cache $ImageName)
    } | ConvertTo-Json
    return
}
if (-not $Cpu.VirtualizationFirmwareEnabled) {
    throw 'Enable AMD SVM / CPU virtualization in BIOS first. No reboot or firmware change was attempted.'
}
$WslConfig = Join-Path $env:USERPROFILE '.wslconfig'
if (-not (Test-Path -LiteralPath $WslConfig) -or
    (Get-Content -Raw -LiteralPath $WslConfig) -notmatch '(?im)^\s*networkingMode\s*=\s*mirrored\s*$') {
    throw 'WSL mirrored networking is required for this test. Preserve existing .wslconfig settings when enabling it.'
}
New-Item -ItemType Directory -Force -Path $Cache | Out-Null
$Image = Join-Path $Cache $ImageName
if (-not (Test-Path -LiteralPath $Image)) {
    Invoke-WebRequest -Uri "https://releases.ubuntu.com/noble/$ImageName" -OutFile $Image
}
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $Image).Hash -ne $ImageHash) {
    throw 'Ubuntu image checksum mismatch. The image was not imported.'
}
$Distros = (& wsl.exe --list --quiet) -replace "`0", ''
if ($LASTEXITCODE -ne 0) { throw 'Unable to list WSL distros.' }
if ($Distros -notcontains $Distro) {
    New-Item -ItemType Directory -Force -Path $Location | Out-Null
    & wsl.exe --import $Distro $Location $Image --version 2
    if ($LASTEXITCODE -ne 0) {
        throw 'WSL import failed. Check Virtual Machine Platform and hypervisor prerequisites; do not replace another distro.'
    }
}
$LinuxScript = & wsl.exe -d $Distro -u root -- wslpath -a (Join-Path $PSScriptRoot 'build-linux.sh')
if ($LASTEXITCODE -ne 0) { throw 'WSL could not resolve the build script path.' }
& wsl.exe -d $Distro -u root -- sh $LinuxScript.Trim()
if ($LASTEXITCODE -ne 0) { throw 'Receiver build failed. Inspect its WSL output.' }
$Config = & wsl.exe -d $Distro -u root -- wslpath -a (Join-Path $PSScriptRoot 'windows-shairport.conf')
if ($LASTEXITCODE -ne 0) { throw 'WSL could not resolve the receiver configuration path.' }
& wsl.exe -d $Distro -u root -- cp $Config.Trim() /opt/jbl-airplay/etc/shairport-sync.conf
if ($LASTEXITCODE -ne 0) { throw 'Receiver configuration copy failed.' }
Write-Output 'Built JBL-AirPlay. Run start-windows-receiver.sh inside this distro for the playback test.'
