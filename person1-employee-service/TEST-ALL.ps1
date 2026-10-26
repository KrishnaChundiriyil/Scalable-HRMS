# Person 1 HRMS - one-file end-to-end API tester for Windows PowerShell
# Run the Spring Boot app first, then run this file in PowerShell.

$Base = "http://localhost:8081"
$script:Passed = 0
$script:Failed = 0
$script:AdminToken = $null
$script:HrToken = $null
$script:EmployeeToken = $null
$script:EmployeeId = $null
$script:HrCreatedEmployeeId = $null
$stamp = Get-Date -Format "yyyyMMddHHmmss"
$script:EmployeeEmail = "p1.employee.$stamp@example.com"
$script:HrEmail = "p1.hr.$stamp@example.com"
$script:HrCreatedEmail = "p1.hrcreated.$stamp@example.com"

function Check($Name, $Condition, $Details = "") {
    if ($Condition) {
        $script:Passed++
        Write-Host "[PASS] $Name $Details" -ForegroundColor Green
    } else {
        $script:Failed++
        Write-Host "[FAIL] $Name $Details" -ForegroundColor Red
    }
}

function Call-Api([string]$Method, [string]$Path, $Body = $null, [string]$Token = $null) {
    $headers = @{}
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }
    $params = @{ Uri = "$Base$Path"; Method = $Method; Headers = $headers; ErrorAction = "Stop" }
    if ($null -ne $Body) {
        $params["ContentType"] = "application/json"
        $params["Body"] = ($Body | ConvertTo-Json -Depth 8 -Compress)
    }
    try {
        $result = Invoke-RestMethod @params
        return @{ Status = 200; Data = $result; Error = "" }
    } catch {
        $status = 0
        try { $status = [int]$_.Exception.Response.StatusCode } catch {}
        $data = $null
        try {
            if ($_.ErrorDetails.Message) { $data = $_.ErrorDetails.Message | ConvertFrom-Json }
        } catch {}
        return @{ Status = $status; Data = $data; Error = $_.Exception.Message }
    }
}

Write-Host "`n=== PERSON 1 HRMS BACKEND TESTS ===" -ForegroundColor Cyan
Write-Host "Target: $Base"

# 1. Health
$r = Call-Api GET "/api/auth/health"
Check "Health endpoint" ($r.Status -eq 200 -and $r.Data.status -eq "UP") "(HTTP $($r.Status))"
if ($r.Status -ne 200) {
    Write-Host "Backend is not responding. Run EmployeeServiceApplication in IntelliJ, then run this file again." -ForegroundColor Yellow
    exit 1
}

# 2. Admin login
$r = Call-Api POST "/api/auth/login" @{ email = "admin@hrms.local"; password = "Admin@12345" }
$script:AdminToken = $r.Data.token
Check "Default Admin login + JWT" ($r.Status -eq 200 -and -not [string]::IsNullOrWhiteSpace($script:AdminToken)) "(HTTP $($r.Status))"
if (-not $script:AdminToken) {
    Write-Host "Admin login failed. Check README credentials / startup logs." -ForegroundColor Yellow
    exit 1
}

# 3. Admin creates an HR account
$r = Call-Api POST "/api/employees" @{
    name = "P1 Test HR"; email = $script:HrEmail; password = "TestHR@1234"
    department = "Human Resources"; jobTitle = "HR Executive"; phone = "9000000001"; role = "HR"
} $script:AdminToken
$hrCreated = ($r.Status -eq 200 -or $r.Status -eq 201) -and $r.Data.employeeId
Check "Admin creates HR account" ([bool]$hrCreated) "(HTTP $($r.Status))"
if ($hrCreated) { $script:HrId = $r.Data.employeeId }

# 4. HR login
$r = Call-Api POST "/api/auth/login" @{ email = $script:HrEmail; password = "TestHR@1234" }
$script:HrToken = $r.Data.token
Check "HR login + JWT" ($r.Status -eq 200 -and -not [string]::IsNullOrWhiteSpace($script:HrToken)) "(HTTP $($r.Status))"

# 5. Public employee registration (role escalation must be ignored)
$r = Call-Api POST "/api/auth/register" @{
    name = "P1 Test Employee"; email = $script:EmployeeEmail; password = "TestEmp@1234"
    department = "Engineering"; jobTitle = "Backend Tester"; phone = "9000000002"; role = "ADMIN"
}
$script:EmployeeId = $r.Data.employeeId
Check "Employee self-registration + generated ID" (($r.Status -eq 200 -or $r.Status -eq 201) -and $script:EmployeeId) "(HTTP $($r.Status))"
Check "Public registration cannot grant Admin role" ($r.Data.role -eq "EMPLOYEE") "(role=$($r.Data.role))"

# 6. Employee login
$r = Call-Api POST "/api/auth/login" @{ email = $script:EmployeeEmail; password = "TestEmp@1234" }
$script:EmployeeToken = $r.Data.token
Check "Employee login + JWT" ($r.Status -eq 200 -and -not [string]::IsNullOrWhiteSpace($script:EmployeeToken)) "(HTTP $($r.Status))"

# 7. Duplicate registration should be rejected
$r = Call-Api POST "/api/auth/register" @{
    name = "Duplicate Person"; email = $script:EmployeeEmail; password = "TestEmp@1234"
    department = "Engineering"; jobTitle = "Tester"; phone = "9000000003"
}
Check "Duplicate email rejected" ($r.Status -eq 409) "(HTTP $($r.Status))"

if ($script:EmployeeId -and $script:EmployeeToken) {
    # 8. Employee views own profile; repeated GET exercises profile cache path
    $r = Call-Api GET "/api/employees/$script:EmployeeId" $null $script:EmployeeToken
    Check "Employee reads own profile" ($r.Status -eq 200 -and $r.Data.employeeId -eq $script:EmployeeId) "(HTTP $($r.Status))"
    $r2 = Call-Api GET "/api/employees/$script:EmployeeId" $null $script:EmployeeToken
    Check "Repeated profile lookup" ($r2.Status -eq 200) "(HTTP $($r2.Status))"

    # 9. Employee updates own profile
    $r = Call-Api PUT "/api/employees/$script:EmployeeId" @{
        name = "P1 Updated Employee"; department = "Engineering"; jobTitle = "Backend Engineer"; phone = "9000000012"
    } $script:EmployeeToken
    Check "Employee updates own profile" ($r.Status -eq 200 -and $r.Data.name -eq "P1 Updated Employee") "(HTTP $($r.Status))"

    # 10. Employee cannot create another account through admin endpoint
    $r = Call-Api POST "/api/employees" @{
        name = "Forbidden Person"; email = "forbidden.$stamp@example.com"; password = "TestEmp@1234"
        department = "Engineering"; jobTitle = "Tester"; phone = "9000000013"; role = "EMPLOYEE"
    } $script:EmployeeToken
    Check "Employee forbidden from creating accounts" ($r.Status -eq 403) "(HTTP $($r.Status))"

    # 11. Employee cannot access another person's profile
    if ($script:HrId) {
        $r = Call-Api GET "/api/employees/$script:HrId" $null $script:EmployeeToken
        Check "Employee forbidden from reading another profile" ($r.Status -eq 403) "(HTTP $($r.Status))"
    }
}

# 12. HR creates a normal employee; HR must not be able to delete
if ($script:HrToken) {
    $r = Call-Api POST "/api/employees" @{
        name = "P1 HR Created Employee"; email = $script:HrCreatedEmail; password = "TestEmp@1234"
        department = "Finance"; jobTitle = "Analyst"; phone = "9000000020"; role = "EMPLOYEE"
    } $script:HrToken
    $script:HrCreatedEmployeeId = $r.Data.employeeId
    Check "HR creates employee account" (($r.Status -eq 200 -or $r.Status -eq 201) -and $script:HrCreatedEmployeeId) "(HTTP $($r.Status))"
    if ($script:HrCreatedEmployeeId) {
        $r = Call-Api DELETE "/api/employees/$script:HrCreatedEmployeeId" $null $script:HrToken
        Check "HR forbidden from deleting employee" ($r.Status -eq 403) "(HTTP $($r.Status))"
    }
}

# 13. Directory and filters
if ($script:AdminToken) {
    $r = Call-Api GET "/api/employees" $null $script:AdminToken
    Check "Employee directory" ($r.Status -eq 200 -and $null -ne $r.Data) "(HTTP $($r.Status))"
    $r = Call-Api GET "/api/employees?name=P1%20Updated&department=Engineering" $null $script:AdminToken
    $found = $false
    if ($r.Status -eq 200) { foreach ($e in $r.Data) { if ($e.email -eq $script:EmployeeEmail) { $found = $true } } }
    Check "Directory name + department filter" $found "(HTTP $($r.Status))"

    # 14. Trie autocomplete (employee names and departments)
    $r = Call-Api GET "/api/employees/suggest?type=employee&prefix=P1" $null $script:AdminToken
    $suggestEmployee = $false
    if ($r.Status -eq 200 -and $r.Data.suggestions) { $suggestEmployee = @($r.Data.suggestions | Where-Object { $_ -eq "P1 Updated Employee" }).Count -gt 0 }
    Check "Employee-name autocomplete" $suggestEmployee "(HTTP $($r.Status))"
    $r = Call-Api GET "/api/employees/suggest?type=department&prefix=Eng" $null $script:AdminToken
    $suggestDept = $false
    if ($r.Status -eq 200 -and $r.Data.suggestions) { $suggestDept = @($r.Data.suggestions | Where-Object { $_ -eq "Engineering" }).Count -gt 0 }
    Check "Department autocomplete" $suggestDept "(HTTP $($r.Status))"
    $r = Call-Api GET "/api/employees/suggest?type=unknown&prefix=x" $null $script:AdminToken
    Check "Invalid autocomplete type rejected" ($r.Status -eq 400) "(HTTP $($r.Status))"

    # 15. Admin-only delete; delete only test-created records
    if ($script:HrCreatedEmployeeId) {
        $r = Call-Api DELETE "/api/employees/$script:HrCreatedEmployeeId" $null $script:AdminToken
        Check "Admin deletes test employee" ($r.Status -eq 200 -or $r.Status -eq 204) "(HTTP $($r.Status))"
    }
    if ($script:EmployeeId) {
        $r = Call-Api DELETE "/api/employees/$script:EmployeeId" $null $script:AdminToken
        Check "Admin deletes test employee" ($r.Status -eq 200 -or $r.Status -eq 204) "(HTTP $($r.Status))"
    }
}

Write-Host "`n========== TEST SUMMARY ==========" -ForegroundColor Cyan
Write-Host "Passed: $script:Passed" -ForegroundColor Green
Write-Host "Failed: $script:Failed" -ForegroundColor $(if ($script:Failed -eq 0) { "Green" } else { "Red" })
if ($script:Failed -eq 0) {
    Write-Host "All automated API checks passed." -ForegroundColor Green
} else {
    Write-Host "Some checks failed. Check the status codes above and IntelliJ Run console." -ForegroundColor Yellow
}
Write-Host "Note: Redis-specific behaviour is only truly exercised when Redis is running on localhost:6379."
