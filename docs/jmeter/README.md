# TokenHub JMeter 场景

`tokenhub-v1.jmx` 包含三个线程组：`normal-purchase`、`seckill-reserve-and-poll`、`model-billing-mock`。默认只启用普通购买，避免一个用户在同一秒杀活动中被“一人一单”约束干扰结果。

运行前，初始化数据库、启动 MySQL/Redis/RabbitMQ/Elasticsearch 和 TokenHub；为 JMeter 用户获取 JWT，并创建允许 `gpt-4o-mini` 的测试 API Key。模型场景必须将 `TOKENHUB_MODEL_BASE_URL` 指向返回稳定 `usage.total_tokens` 的受控 OpenAI-compatible mock，不能使用生产 Provider。

```powershell
jmeter -n -t docs/jmeter/tokenhub-v1.jmx -Jhost=127.0.0.1 -Jport=8080 -Jjwt='<JWT>' -JapiKey='<API_KEY>' -Jthreads=10 -Jloops=1 -l docs/jmeter/results/run.jtl -e -o docs/jmeter/results/html
```

每次只启用一个场景并使用新的数据库数据：普通购买需确保套餐库存充足；秒杀需在活动窗口内并让每个线程使用不同用户；模型场景需为该用户准备足够 Token。保留 `.jtl`、HTML 报告和下方模板中的实际数值，不以预设并发或目标数值替代结果。
