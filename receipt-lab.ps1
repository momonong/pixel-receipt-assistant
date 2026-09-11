[CmdletBinding(PositionalBinding=$false)]
param(
    [Parameter(Position=0)][ValidateSet('serve','status','list','upload','run','case','result','note','nano-probe')][string]$Command = 'serve',
    [string]$Url = 'http://127.0.0.1:8765',
    [Parameter(ValueFromRemainingArguments=$true)][string[]]$LabArguments
)
$ErrorActionPreference = 'Stop'
$taskPython = Join-Path $PSScriptRoot '.gradle/lab-venv/Scripts/python.exe'
if (!(Test-Path -LiteralPath $taskPython)) {
    & python -m venv (Join-Path $PSScriptRoot '.gradle/lab-venv')
    if ($LASTEXITCODE -ne 0) { throw '無法建立 Python 環境。' }
    & $taskPython -m pip install -r (Join-Path $PSScriptRoot 'tools/receipt_lab/requirements.txt')
    if ($LASTEXITCODE -ne 0) { throw '無法安裝測試介面依賴。' }
}
Push-Location $PSScriptRoot
try {
    if (!$env:RECEIPT_LAB_ADB -and (Test-Path -LiteralPath '.receipt-lab/config.json')) {
        $taskConfig = Get-Content .receipt-lab/config.json -Raw | ConvertFrom-Json
        $env:RECEIPT_LAB_ADB = $taskConfig.adb
    }
    & $taskPython -X utf8 -m tools.receipt_lab --url $Url $Command @LabArguments
    exit $LASTEXITCODE
}
finally { Pop-Location }
