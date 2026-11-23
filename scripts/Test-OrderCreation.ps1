#requires -Version 5.1

# 없는 응답 항목을 읽거나 명령이 실패하면 즉시 중단합니다.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# 실행하면 실제 주문 두 건이 추가됩니다. 초기 버킷 매핑 기준입니다.
$orderTestCases = @(
    @{ UserId = 1; Amount = [decimal]12000; ExpectedBucket = 3 },
    @{ UserId = 101; Amount = [decimal]25000; ExpectedBucket = 10 }
)

foreach ($orderTestCase in $orderTestCases) {
    # 사용자가 보내야 하는 값만 JSON으로 만듭니다.
    $orderRequestBody = @{
        userId = $orderTestCase.UserId
        amount = $orderTestCase.Amount
    } | ConvertTo-Json

    $orderRequestOptions = @{
        Uri             = 'http://127.0.0.1:8080/orders'
        Method          = 'Post'
        ContentType     = 'application/json'
        Body            = $orderRequestBody
        UseBasicParsing = $true
        TimeoutSec      = 10
        ErrorAction     = 'Stop'
    }

    $orderResponse = Invoke-WebRequest @orderRequestOptions

    if ($orderResponse.StatusCode -ne 201) {
        throw "Expected HTTP 201, got $($orderResponse.StatusCode) for userId=$($orderTestCase.UserId)"
    }

    $createdOrder = $orderResponse.Content | ConvertFrom-Json

    if ($createdOrder.userId -ne $orderTestCase.UserId) {
        throw "Unexpected userId: $($createdOrder.userId)"
    }

    # 12000과 12000.00은 같은 금액이므로 숫자로 비교합니다.
    if ([decimal]$createdOrder.amount -ne $orderTestCase.Amount) {
        throw "Unexpected amount for userId=$($orderTestCase.UserId): $($createdOrder.amount)"
    }

    if ($createdOrder.bucketId -ne $orderTestCase.ExpectedBucket) {
        throw "Unexpected bucketId for userId=$($orderTestCase.UserId): $($createdOrder.bucketId)"
    }

    if ($createdOrder.status -cne 'CREATED') {
        throw "Unexpected order status: $($createdOrder.status)"
    }

    if ([string]::IsNullOrWhiteSpace([string]$createdOrder.orderId)) {
        throw "Missing orderId for userId=$($orderTestCase.UserId)"
    }

    # 해당 요청의 모든 검사를 통과한 뒤에만 성공을 출력합니다.
    Write-Output "PASS: userId=$($createdOrder.userId), bucketId=$($createdOrder.bucketId), orderId=$($createdOrder.orderId)"
}
