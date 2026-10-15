param(
    [Parameter(Mandatory = $true)] [string] $OutputPath,
    [int] $DurationSeconds = 30
)

$password = ConvertTo-SecureString 'guest' -AsPlainText -Force
$credential = New-Object System.Management.Automation.PSCredential('guest', $password)
$uri = 'http://127.0.0.1:15672/api/queues/%2F/seckill.order.queue'
$end = (Get-Date).AddSeconds($DurationSeconds)
$rows = [System.Collections.Generic.List[object]]::new()

while ((Get-Date) -lt $end) {
    try {
        $queue = Invoke-RestMethod -Uri $uri -Credential $credential -TimeoutSec 2
        $stats = $queue.message_stats
        $rows.Add([pscustomobject]@{
            timestamp = (Get-Date).ToUniversalTime().ToString('o')
            messages_ready = $queue.messages_ready
            messages_unacknowledged = $queue.messages_unacknowledged
            consumers = $queue.consumers
            publish_rate = if ($null -eq $stats.publish_details) { 0 } else { $stats.publish_details.rate }
            deliver_rate = if ($null -eq $stats.deliver_get_details) { 0 } else { $stats.deliver_get_details.rate }
            ack_rate = if ($null -eq $stats.ack_details) { 0 } else { $stats.ack_details.rate }
        })
    } catch {
        $rows.Add([pscustomobject]@{
            timestamp = (Get-Date).ToUniversalTime().ToString('o')
            messages_ready = -1
            messages_unacknowledged = -1
            consumers = -1
            publish_rate = -1
            deliver_rate = -1
            ack_rate = -1
        })
    }
    Start-Sleep -Milliseconds 100
}

$rows | Export-Csv -LiteralPath $OutputPath -NoTypeInformation -Encoding utf8
