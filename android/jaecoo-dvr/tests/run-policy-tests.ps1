$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot
$javaBin=if($env:JAVA_HOME){Join-Path $env:JAVA_HOME 'bin'}else{Split-Path (Get-Command javac.exe).Source}
$out=Join-Path $project ('.local-build/policy-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $out -Force | Out-Null
& (Join-Path $javaBin 'javac.exe') -encoding UTF-8 -source 8 -target 8 -d $out (Join-Path $project 'src/dev/local/jaecoorecorder/DvrAutomation.java') (Join-Path $PSScriptRoot 'DvrAutomationHostTest.java')
if($LASTEXITCODE){throw 'Policy compilation failed'}
& (Join-Path $javaBin 'java.exe') -cp $out DvrAutomationHostTest
if($LASTEXITCODE){throw 'Policy test failed'}
