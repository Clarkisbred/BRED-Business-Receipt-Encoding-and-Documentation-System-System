[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Version,

    [switch]$ValidateOnly,

    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$pomPath = Join-Path $projectRoot 'pom.xml'
$targetPath = Join-Path $projectRoot 'target'
$termsPath = Join-Path (Join-Path $projectRoot 'installer') "BRED-Terms-$Version.rtf"
$termsSourcePath = Join-Path (Join-Path $projectRoot 'installer') "BRED-Terms-$Version.md"
$upgradeCode = '7B6F2C1E-4A8D-4F3B-9C1A-5E2D8F0A1B34'
$applicationName = 'BRED'
$mainClass = 'com.bred.rms.Main'

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'The Windows MSI build must run on Windows.'
}

if ($Version -notmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$') {
    throw "Version '$Version' must be three numeric components, for example 1.2.3."
}

foreach ($component in $Version.Split('.')) {
    $number = 0
    if (-not [uint16]::TryParse($component, [ref]$number)) {
        throw "Version component '$component' must be between 0 and 65535 for Windows MSI."
    }
}

if (-not (Test-Path -LiteralPath $pomPath -PathType Leaf)) {
    throw "Maven project file not found: $pomPath"
}
if (-not (Test-Path -LiteralPath $termsPath -PathType Leaf)) {
    throw "Version-matched installer terms file not found: $termsPath"
}
if (-not (Test-Path -LiteralPath $termsSourcePath -PathType Leaf)) {
    throw "Canonical version-matched terms source not found: $termsSourcePath"
}
$termsContent = [System.IO.File]::ReadAllText($termsSourcePath)
if ($termsContent -notmatch "(?m)^\*\*Version:\*\*\s+$([regex]::Escape($Version))\s*$") {
    throw "The canonical terms source must identify version $Version."
}
if ($termsContent -match '\[Insert [^\]]+\]') {
    throw 'The canonical terms source contains unresolved placeholders.'
}
if (-not [System.IO.File]::ReadAllText($termsPath).StartsWith('{\rtf1')) {
    throw "The installer terms file is not a valid RTF document: $termsPath"
}

$jpackageCommand = Get-Command jpackage -CommandType Application -ErrorAction Stop
$jpackagePath = $jpackageCommand.Source
$jdkBinPath = Split-Path -Parent $jpackagePath
$jdkRootPath = Split-Path -Parent $jdkBinPath
$javaPath = Join-Path $jdkBinPath 'java.exe'
$jdkReleasePath = Join-Path $jdkRootPath 'release'
if (-not (Test-Path -LiteralPath $javaPath -PathType Leaf)) {
    throw "The jpackage JDK does not contain java.exe beside jpackage: $jdkBinPath"
}
if (-not (Test-Path -LiteralPath $jdkReleasePath -PathType Leaf)) {
    throw "Java release metadata not found beside jpackage: $jdkReleasePath"
}

$jpackageVersion = (& $jpackagePath --version | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $jpackageVersion -notmatch '^21(?:\.|$)') {
    throw "Java 21 jpackage is required; found '$jpackageVersion'."
}

$jdkRelease = [System.IO.File]::ReadAllText($jdkReleasePath)
if ($jdkRelease -notmatch '(?m)^JAVA_VERSION="21(?:\.|")') {
    throw 'The Java runtime beside jpackage must be Java 21.'
}

$mavenCommand = Get-Command mvn -ErrorAction Stop
Push-Location $projectRoot
try {
    & $mavenCommand.Source -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven package failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

[xml]$pom = [System.IO.File]::ReadAllText($pomPath)
$mavenArtifact = [string]$pom.project.artifactId
$mavenVersion = [string]$pom.project.version
if ($mavenVersion -ne $Version) {
    throw "Requested MSI version $Version does not match Maven version $mavenVersion."
}
$shadedJar = Join-Path $targetPath "$mavenArtifact-$mavenVersion-shaded.jar"
if (-not (Test-Path -LiteralPath $shadedJar -PathType Leaf)) {
    $shadedJar = Join-Path $targetPath "$mavenArtifact-$mavenVersion.jar"
}
if (-not (Test-Path -LiteralPath $shadedJar -PathType Leaf)) {
    throw "Expected shaded application JAR was not produced: $shadedJar"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($shadedJar)
try {
    $entryNames = @($archive.Entries | ForEach-Object { $_.FullName })
    $manifestEntry = $archive.GetEntry('META-INF/MANIFEST.MF')
    if ($null -eq $manifestEntry) {
        throw 'The shaded JAR has no META-INF/MANIFEST.MF.'
    }

    $manifestStream = $manifestEntry.Open()
    $manifestReader = New-Object System.IO.StreamReader($manifestStream)
    try {
        $manifest = $manifestReader.ReadToEnd()
    }
    finally {
        $manifestReader.Dispose()
    }
}
finally {
    $archive.Dispose()
}

if ($manifest -notmatch "(?m)^Main-Class:\s*$([regex]::Escape($mainClass))\s*$") {
    throw "The shaded JAR manifest must declare Main-Class: $mainClass."
}

$requiredJarEntries = @(
    "$($mainClass.Replace('.', '/')).class",
    'web-app/index.html',
    'org/cef/CefApp.class',
    'com/formdev/flatlaf/FlatLaf.class',
    'com/fasterxml/jackson/databind/ObjectMapper.class',
    'org/apache/poi/ss/usermodel/Workbook.class',
    'com/opencsv/CSVReader.class',
    'org/apache/pdfbox/text/PDFTextStripper.class'
)
foreach ($requiredEntry in $requiredJarEntries) {
    if ($entryNames -notcontains $requiredEntry) {
        throw "Required application or bundled dependency is missing from the shaded JAR: $requiredEntry"
    }
}

$jcefNativeEntries = @($entryNames | Where-Object {
    $_ -match '^jcef-natives-windows-amd64-.*\.tar\.gz$'
})
if ($jcefNativeEntries.Count -ne 1) {
    throw 'The shaded JAR must contain exactly one bundled Windows AMD64 JCEF native runtime.'
}

$reactScriptEntries = @($entryNames | Where-Object {
    $_ -match '^web-app/assets/.*\.js$'
})
if ($reactScriptEntries.Count -eq 0) {
    throw 'The shaded JAR must contain the built React JavaScript assets.'
}

$ocrPayloadEntries = @($entryNames | Where-Object {
    $_ -match '(^|/)tessdata/.*\.traineddata$|(^|/)libtesseract.*\.dll$|(^|/)libleptonica.*\.dll$'
})
if ($ocrPayloadEntries.Count -gt 0) {
    throw "The shaded JAR must not bundle Tesseract engines or trained data: $($ocrPayloadEntries -join ', ')"
}

$installerPath = Join-Path (Join-Path $targetPath 'installers') "$applicationName-Setup-$Version.msi"
if ((Test-Path -LiteralPath $installerPath) -and -not $Force) {
    throw "Installer already exists: $installerPath. Use -Force only if you intend to replace it."
}

$validationStage = Join-Path $targetPath 'msi-build-validation'
$appImageArguments = @(
    '--type', 'app-image',
    '--name', $applicationName,
    '--app-version', $Version,
    '--input', (Join-Path $validationStage 'input'),
    '--main-jar', (Split-Path -Leaf $shadedJar),
    '--main-class', $mainClass,
    '--icon', (Join-Path $PSScriptRoot '..\src\main\resources\BREDlogo.ico'),
    '--dest', (Join-Path $validationStage 'image')
)
$msiArguments = @(
    '--type', 'msi',
    '--name', $applicationName,
    '--app-version', $Version,
    '--app-image', (Join-Path (Join-Path $validationStage 'image') $applicationName),
    '--dest', (Join-Path $validationStage 'msi'),
    '--win-upgrade-uuid', $upgradeCode,
    '--install-dir', 'BRED',
    '--win-dir-chooser',
    '--license-file', $termsPath
)

function Assert-OptionValue {
    param(
        [string[]]$Arguments,
        [string]$Option,
        [string]$ExpectedValue
    )

    $index = [Array]::IndexOf($Arguments, $Option)
    if ($index -lt 0 -or $index + 1 -ge $Arguments.Length -or $Arguments[$index + 1] -ne $ExpectedValue) {
        throw "Invalid jpackage arguments: expected '$Option $ExpectedValue'."
    }
}

Assert-OptionValue -Arguments $appImageArguments -Option '--type' -ExpectedValue 'app-image'
Assert-OptionValue -Arguments $appImageArguments -Option '--name' -ExpectedValue $applicationName
Assert-OptionValue -Arguments $appImageArguments -Option '--app-version' -ExpectedValue $Version
Assert-OptionValue -Arguments $appImageArguments -Option '--main-jar' -ExpectedValue (Split-Path -Leaf $shadedJar)
Assert-OptionValue -Arguments $appImageArguments -Option '--main-class' -ExpectedValue $mainClass
Assert-OptionValue -Arguments $appImageArguments -Option '--input' -ExpectedValue (Join-Path $validationStage 'input')
Assert-OptionValue -Arguments $appImageArguments -Option '--dest' -ExpectedValue (Join-Path $validationStage 'image')
Assert-OptionValue -Arguments $msiArguments -Option '--type' -ExpectedValue 'msi'
Assert-OptionValue -Arguments $msiArguments -Option '--name' -ExpectedValue $applicationName
Assert-OptionValue -Arguments $msiArguments -Option '--app-version' -ExpectedValue $Version
Assert-OptionValue -Arguments $msiArguments -Option '--app-image' -ExpectedValue (Join-Path (Join-Path $validationStage 'image') $applicationName)
Assert-OptionValue -Arguments $msiArguments -Option '--dest' -ExpectedValue (Join-Path $validationStage 'msi')
Assert-OptionValue -Arguments $msiArguments -Option '--win-upgrade-uuid' -ExpectedValue $upgradeCode
Assert-OptionValue -Arguments $msiArguments -Option '--install-dir' -ExpectedValue 'BRED'
Assert-OptionValue -Arguments $msiArguments -Option '--license-file' -ExpectedValue $termsPath

if ($msiArguments -contains '--win-per-user-install') {
    throw 'The MSI must remain per-machine; do not pass --win-per-user-install.'
}
if ($appImageArguments -notcontains '--dest' -or $msiArguments -notcontains '--dest') {
    throw 'Both jpackage invocations must specify their staging destination.'
}
if ($msiArguments -notcontains '--win-dir-chooser') {
    throw 'The MSI must allow the user to select the existing installation directory.'
}

$wixBinPath = $null
if ($env:WIX) {
    $candidateWixBin = Join-Path $env:WIX 'bin'
    if ((Test-Path (Join-Path $candidateWixBin 'candle.exe')) -and
        (Test-Path (Join-Path $candidateWixBin 'light.exe'))) {
        $wixBinPath = $candidateWixBin
    }
}

if (-not $wixBinPath) {
    $candle = Get-Command candle.exe -ErrorAction SilentlyContinue
    $light = Get-Command light.exe -ErrorAction SilentlyContinue
    if ($candle -and $light) {
        $wixBinPath = Split-Path -Parent $candle.Source
    }
}

if ($ValidateOnly) {
    Write-Output "Preflight passed for BRED $Version."
    Write-Output "Shaded JAR: $shadedJar"
    Write-Output "UpgradeCode: $upgradeCode"
    Write-Output "Installer terms: $termsPath"
    Write-Output 'MSI scope: per-machine (no --win-per-user-install argument)'
    Write-Output 'The MSI will offer a directory chooser; select D:\BRED to target the existing installation.'
    Write-Output "Expected output: $installerPath"
    if ($wixBinPath) {
        Write-Output "WiX tools found: $wixBinPath"
    }
    else {
        Write-Warning 'WiX candle.exe and light.exe are not available. Install WiX Toolset 3 before creating the MSI.'
    }
    Write-Output ('App-image arguments: ' + ($appImageArguments -join ' '))
    Write-Output ('MSI arguments: ' + ($msiArguments -join ' '))
    Write-Output 'Validation-only mode did not invoke jpackage and did not generate an MSI.'
    return
}

if (-not $wixBinPath) {
    throw 'WiX Toolset 3 candle.exe and light.exe are required on PATH (or under the WIX environment variable) to create an MSI.'
}
$env:PATH = "$wixBinPath;$env:PATH"

$workPath = Join-Path $targetPath ("msi-build-" + [guid]::NewGuid().ToString('N'))
$inputPath = Join-Path $workPath 'input'
$imageDestination = Join-Path $workPath 'image'
$imagePath = Join-Path $imageDestination $applicationName
$msiDestination = Join-Path $workPath 'msi'
try {
    New-Item -ItemType Directory -Path $inputPath -Force | Out-Null
    Copy-Item -LiteralPath $shadedJar -Destination $inputPath

    $appImageArguments = @(
        '--type', 'app-image',
        '--name', $applicationName,
        '--app-version', $Version,
        '--input', $inputPath,
        '--main-jar', (Split-Path -Leaf $shadedJar),
        '--main-class', $mainClass,
        '--icon', (Join-Path $PSScriptRoot '..\src\main\resources\BREDlogo.ico'),
        '--dest', $imageDestination
    )
    & $jpackagePath @appImageArguments
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage app-image creation failed with exit code $LASTEXITCODE."
    }

    $launcherPath = Join-Path $imagePath "$applicationName.exe"
    $runtimePath = Join-Path $imagePath 'runtime\bin\server\jvm.dll'
    $packagedJarPath = Join-Path (Join-Path $imagePath 'app') (Split-Path -Leaf $shadedJar)
    foreach ($requiredPath in @($launcherPath, $runtimePath, $packagedJarPath)) {
        if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
            throw "jpackage app image is incomplete; required file not found: $requiredPath"
        }
    }

    $msiArguments = @(
        '--type', 'msi',
        '--name', $applicationName,
        '--app-version', $Version,
        '--app-image', $imagePath,
        '--dest', $msiDestination,
        '--win-upgrade-uuid', $upgradeCode,
        '--install-dir', 'BRED',
        '--win-dir-chooser',
        '--license-file', $termsPath
    )
    Assert-OptionValue -Arguments $msiArguments -Option '--type' -ExpectedValue 'msi'
    Assert-OptionValue -Arguments $msiArguments -Option '--name' -ExpectedValue $applicationName
    Assert-OptionValue -Arguments $msiArguments -Option '--app-version' -ExpectedValue $Version
    Assert-OptionValue -Arguments $msiArguments -Option '--app-image' -ExpectedValue $imagePath
    Assert-OptionValue -Arguments $msiArguments -Option '--win-upgrade-uuid' -ExpectedValue $upgradeCode
    Assert-OptionValue -Arguments $msiArguments -Option '--install-dir' -ExpectedValue 'BRED'
    Assert-OptionValue -Arguments $msiArguments -Option '--license-file' -ExpectedValue $termsPath
    if ($msiArguments -contains '--win-per-user-install' -or $msiArguments -notcontains '--win-dir-chooser') {
        throw 'The MSI must remain per-machine and provide a directory chooser.'
    }

    & $jpackagePath @msiArguments
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage MSI creation failed with exit code $LASTEXITCODE."
    }

    $expectedStagedMsi = Join-Path $msiDestination "$applicationName-$Version.msi"
    if (-not (Test-Path -LiteralPath $expectedStagedMsi -PathType Leaf)) {
        throw "jpackage did not produce the expected installer: $expectedStagedMsi"
    }
    if ((Get-Item -LiteralPath $expectedStagedMsi).Length -le 0) {
        throw 'jpackage produced an empty MSI.'
    }

    $installerDirectory = Split-Path -Parent $installerPath
    New-Item -ItemType Directory -Path $installerDirectory -Force | Out-Null
    if ($Force) {
        Copy-Item -LiteralPath $expectedStagedMsi -Destination $installerPath -Force
    }
    else {
        Copy-Item -LiteralPath $expectedStagedMsi -Destination $installerPath
    }
}
finally {
    if (Test-Path -LiteralPath $workPath) {
        Remove-Item -LiteralPath $workPath -Recurse -Force
    }
}

Write-Output "Created $installerPath"
Write-Warning 'The MSI has not been installed or tested as an upgrade. Verify its metadata and test against the currently installed BRED version in an isolated environment.'

