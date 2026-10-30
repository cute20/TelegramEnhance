param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MavenHome = $env:MAVEN_HOME,
    [string]$MavenSettings
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    if ($JavaHome) { $env:JAVA_HOME = $JavaHome; $env:Path = "$JavaHome\bin;$env:Path" }
    $missing = @()
    $node = Get-Command node.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    $pnpm = Get-Command pnpm.cmd,pnpm.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    $mavenCommand = $null
    if (!$MavenHome) { $MavenHome = $env:M2_HOME }
    if ($MavenHome) {
        $mavenPath = Join-Path $MavenHome 'bin/mvn.cmd'
        if (Test-Path -LiteralPath $mavenPath -PathType Leaf) {
            $mavenCommand = (Resolve-Path -LiteralPath $mavenPath).ProviderPath
        } else {
            $missing += 'Maven：MavenHome / MAVEN_HOME / M2_HOME 必须指向包含 bin/mvn.cmd 的 Maven 安装目录。'
        }
    } else {
        $maven = Get-Command mvn.cmd -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($maven) { $mavenCommand = $maven.Source }
        else { $missing += 'Maven：请安装 Maven 3.9，设置 MAVEN_HOME 或将其 bin 目录加入 PATH。' }
    }
    $mavenArguments = @()
    if ($MavenSettings) {
        if (Test-Path -LiteralPath $MavenSettings -PathType Leaf) {
            $MavenSettings = (Resolve-Path -LiteralPath $MavenSettings).ProviderPath
            $mavenArguments += @('-s', $MavenSettings)
        } else {
            $missing += 'Maven：MavenSettings 必须指向已存在的 settings.xml 文件。'
        }
    }
    if (!$node) { $missing += 'Node.js：请安装 Node.js 22.12+（建议 24 LTS），并加入 PATH。' }
    if (!$pnpm) { $missing += 'pnpm：安装 Node.js 后，在 PowerShell 执行 npm.cmd install --global pnpm@11.25.0，并将 npm 全局命令目录加入 PATH。' }
    if ($JavaHome) {
        if (!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe') -PathType Leaf) -or
            !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe') -PathType Leaf)) {
            $missing += 'JDK：JAVA_HOME 必须指向有效的 JDK 21 根目录（包含 bin/java.exe 和 bin/javac.exe）。'
        }
    } elseif (!(Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue) -or
              !(Get-Command javac.exe -CommandType Application -ErrorAction SilentlyContinue)) {
        $missing += 'JDK：请安装 JDK 21，并配置 JAVA_HOME 或将其 bin 目录加入 PATH。'
    }
    if ($missing.Count -gt 0) {
        Write-Host '构建环境检查未通过：' -ForegroundColor Red
        foreach ($item in $missing) { Write-Host "- $item" }
        Write-Host '配置完成后，重新打开终端或项目文件夹，再双击 build.cmd。详见 README.md 的本地环境说明。'
        exit 1
    }
    Write-Host "Maven：$mavenCommand"
    if ($MavenSettings) { Write-Host "Maven 配置：$MavenSettings" }
    else { Write-Host 'Maven 配置：使用安装目录 conf/settings.xml 和用户 .m2/settings.xml。' }
    & $pnpm.Source --dir frontend install --frozen-lockfile
    if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败' }
    & $pnpm.Source --dir frontend test
    if ($LASTEXITCODE -ne 0) { throw '前端行为测试失败' }
    & $pnpm.Source --dir frontend build
    if ($LASTEXITCODE -ne 0) { throw '前端构建失败' }
    $mavenArguments += @('-B', 'verify', 'dependency:copy-dependencies', '-DincludeScope=runtime', '-DoutputDirectory=target/lib')
    & $mavenCommand @mavenArguments
    if ($LASTEXITCODE -ne 0) { throw '后端构建或测试失败' }
} finally { Pop-Location }
