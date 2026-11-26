param(
    [Parameter(Mandatory = $true)]
    [string] $DataDirectory
)

$ErrorActionPreference = 'Stop'
$expectedMainClass = 'com.bred.rms.ui.LocalWorkflowHarness'
$projectRoot = Split-Path -Parent $PSScriptRoot
$effectivePomPath = Join-Path $env:TEMP ('bred-harness-effective-pom-' + [guid]::NewGuid().ToString('N') + '.xml')
$dataPath = [System.IO.Path]::GetFullPath($DataDirectory)
$tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd(
    [System.IO.Path]::DirectorySeparatorChar,
    [System.IO.Path]::AltDirectorySeparatorChar)
$dataParent = [System.IO.Path]::GetDirectoryName($dataPath).TrimEnd(
    [System.IO.Path]::DirectorySeparatorChar,
    [System.IO.Path]::AltDirectorySeparatorChar)

if ($dataParent -ne $tempRoot -or
    [System.IO.Path]::GetFileName($dataPath) -notmatch '^bred-ai-ui-test-.+') {
    throw 'DataDirectory must be a new direct child of the system temp directory named bred-ai-ui-test-<unique-id>.'
}
if (Test-Path -LiteralPath $dataPath) {
    throw 'DataDirectory already exists; choose a fresh unique path.'
}

$env:RMS_AI_PROVIDER = 'ollama'
$env:RMS_CLOUD_AI_ATTACHMENTS_CONSENT = 'false'
$env:RMS_OLLAMA_BASE_URL = 'http://localhost:11434'
$env:RMS_OLLAMA_MODEL = 'qwen2.5:3b'

Push-Location $projectRoot
try {
    & mvn '-Plocal-workflow-harness' 'help:effective-pom' "-Doutput=$effectivePomPath" '-q'
    if ($LASTEXITCODE -ne 0) {
        throw "Maven effective-POM verification failed with exit code $LASTEXITCODE."
    }

    [xml] $effectivePom = Get-Content -LiteralPath $effectivePomPath -Raw
    $execution = $effectivePom.SelectSingleNode(
        "//*[local-name()='plugin'][*[local-name()='artifactId' and text()='exec-maven-plugin']]/*[local-name()='executions']/*[local-name()='execution'][*[local-name()='id' and text()='local-workflow-harness']]")
    if ($null -eq $execution) {
        throw 'The effective POM has no local-workflow-harness Exec Maven Plugin execution.'
    }

    $mainClassNode = $execution.SelectSingleNode("./*[local-name()='configuration']/*[local-name()='mainClass']")
    $classpathScopeNode = $execution.SelectSingleNode("./*[local-name()='configuration']/*[local-name()='classpathScope']")
    if ($null -eq $mainClassNode -or $mainClassNode.InnerText.Trim() -ne $expectedMainClass) {
        $actualMainClass = if ($null -eq $mainClassNode) { '<missing>' } else { $mainClassNode.InnerText.Trim() }
        throw "Refusing to launch: expected $expectedMainClass but Maven resolves $actualMainClass."
    }
    if ($null -eq $classpathScopeNode -or $classpathScopeNode.InnerText.Trim() -ne 'test') {
        throw 'Refusing to launch: the harness execution must use the test classpath.'
    }
    Write-Host "Verified Exec Maven Plugin main class: $($mainClassNode.InnerText.Trim())"

    if ($env:RMS_AI_PROVIDER -ne 'ollama' -or
        $env:RMS_CLOUD_AI_ATTACHMENTS_CONSENT -ne 'false' -or
        $env:RMS_OLLAMA_BASE_URL -ne 'http://localhost:11434') {
        throw 'Refusing to launch: local Ollama must be selected and cloud attachment consent disabled.'
    }
    Write-Host 'Verified AI provider: Ollama; cloud attachment consent: false.'

    & mvn '-Plocal-workflow-harness' 'test-compile' 'exec:java@local-workflow-harness' "-Dexec.args=$dataPath"
    if ($LASTEXITCODE -ne 0) {
        throw "Local workflow harness exited with code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
    if (Test-Path -LiteralPath $effectivePomPath) {
        Remove-Item -LiteralPath $effectivePomPath
    }
}
