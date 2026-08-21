# databases.json / oracle.env 비밀번호를 B64(...) 형식으로 인코딩/디코딩하는 도구.
# 암호화가 아니라 인코딩입니다 - 파일을 얼핏 봤을 때 비밀번호가 그대로 보이지 않게만 해줍니다.
#
# 사용법:
#   .\b64-tool.ps1 encode <평문>       -> B64(...) 값 출력
#   .\b64-tool.ps1 decode <B64(...)>   -> 평문 출력

param(
    [Parameter(Mandatory=$true, Position=0)]
    [ValidateSet("encode", "decode")]
    [string]$Mode,

    [Parameter(Mandatory=$true, Position=1)]
    [string]$Value
)

if ($Mode -eq "encode") {
    $b64 = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($Value))
    Write-Output "B64($b64)"
} else {
    $raw = $Value
    if ($raw.StartsWith("B64(") -and $raw.EndsWith(")")) {
        $raw = $raw.Substring(4, $raw.Length - 5)
    }
    Write-Output ([System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($raw)))
}
