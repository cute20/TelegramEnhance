param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    if ($JavaHome) { $env:JAVA_HOME = $JavaHome; $env:Path = "$JavaHome\bin;$env:Path" }
    & pnpm --dir frontend install --frozen-lockfile
    if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败' }
    & pnpm --dir frontend test
    if ($LASTEXITCODE -ne 0) { throw '前端行为测试失败' }
    & pnpm --dir frontend build
    if ($LASTEXITCODE -ne 0) { throw '前端构建失败' }
    & mvn -s .mvn/settings.xml -gs .mvn/settings.xml -B verify dependency:copy-dependencies '-DincludeScope=runtime' '-DoutputDirectory=target/lib'
    if ($LASTEXITCODE -ne 0) { throw '后端构建或测试失败' }
} finally { Pop-Location }
