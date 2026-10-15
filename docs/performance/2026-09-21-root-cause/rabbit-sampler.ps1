param(
    [string]$Output = "rabbit.csv",
    [int]$Seconds = 30
)

$endpoint = 'http://127.0.0.1:15672/api/queues/%2F/seckill.order.queue'
$auth = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('guest:guest'))
$headers = @{ Authorization = $auth }
$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add('timestamp,messages,messages_ready,messages_unacknowledged,consumers,publish_rate,deliver_rate,ack_rate')
$deadline = (Get-Date).AddSeconds($Seconds)
while ((Get-Date) -lt $deadline) {
    try {
        $queue = Invoke-RestMethod -Uri $endpoint -Headers $headers -TimeoutSec 2
        $publish = if ($null -eq $queue.message_stats) { 0 } else { $queue.message_stats.publish_details.rate }
        $deliver = if ($null -eq $queue.message_stats) { 0 } else { $queue.message_stats.deliver_get_details.rate }
        $ack = if ($null -eq $queue.message_stats) { 0 } else { $queue.message_stats.ack_details.rate }
        $lines.Add(('{0},{1},{2},{3},{4},{5},{6},{7}' -f (Get-Date).ToString('o'), $queue.messages, $queue.messages_ready, $queue.messages_unacknowledged, $queue.consumers, $publish, $deliver, $ack))
    } catch {
        $lines.Add(('{0},ERROR,ERROR,ERROR,ERROR,ERROR,ERROR,ERROR' -f (Get-Date).ToString('o')))
    }
    Start-Sleep -Milliseconds 100
}
[IO.File]::WriteAllLines($Output, $lines)
