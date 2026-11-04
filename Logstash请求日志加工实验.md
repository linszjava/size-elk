# Logstash 请求日志加工实验

## 1 这次要解决什么问题

当前 `/api/orders/1003` 返回 404，但网关记录的是 INFO；只筛选 ERROR 会漏掉失败请求。这次引入 Logstash，把网关的请求完成日志转为可以统一分析的 HTTP 事件。

链路变为：

```text
Java 服务 → 项目 logs 文件夹 → Elastic Agent（Filebeat）
                                      ↓
                                  Logstash
                        补字段、转换单位、标记请求结果
                                      ↓
                              Elasticsearch → Kibana
```

本实验使用已有的三个 Java 服务、ES、Kibana、Agent，仅新增 Logstash 9.5.3。Nacos 和 Java 服务仍按现有方式运行。Docker 需要额外可用内存，Logstash 本配置的堆为 512 MB，实际占用会更高。

## 2 处理前后有什么区别

以网关记录的一次 404 为例，下面省略其他字段：

处理前：

```json
{"requestId":"demo-404","path":"/api/orders/1003","status":404,"duration_ms":77}
```

处理后保留上述字段，并增加：

```json
{
  "http": {
    "request": {"id":"demo-404"},
    "response": {"status_code":404}
  },
  "url": {"path":"/api/orders/1003"},
  "event": {
    "action":"gateway_request_completed",
    "outcome":"failure",
    "duration":77000000
  },
  "tags":["http_4xx"],
  "labels":{"processing_pipeline":"logstash_requests_v1"}
}
```

`event.duration` 的 ECS 单位是纳秒，77 毫秒乘以 1,000,000。`log.level` 保持 INFO：日志级别和 HTTP 请求是否成功是两个概念。

| 原始字段或条件 | Logstash 的处理 |
|---|---|
| `requestId` | 复制到 `http.request.id`，保留原字段，现有查询继续有效 |
| 网关日志存在 `status` | 作为请求完成事件，增加 `event.action` |
| `status` | 复制到 `http.response.status_code` 并转为整数 |
| `path` | 复制到 `url.path` |
| `duration_ms` | 转换为纳秒，写入 `event.duration` |
| 状态码 200～399 | 标记 `success`，添加 `http_success` 标签 |
| 状态码 400～499 | 标记 `failure`，添加 `http_4xx` 标签 |
| 状态码 500 及以上 | 标记 `failure`，添加 `http_5xx` 标签 |
| 其他状态，如 0 | 标记 `unknown` |
| 所有经过该管道的日志 | 添加 `labels.processing_pipeline`，证明经过了 Logstash |

此处 outcome 是按“网关请求是否成功”的业务口径定义。404 不会被改成 ERROR，Logstash 也不会凭空生成异常原因。

## 3 配置文件已经准备好

在项目 `elastic-local` 中新增以下文件：

```text
elastic-local/
├── compose.yml                     原来的基础配置
├── elastic-agent.yml               原来的 Agent 直连 ES 配置
├── compose.logstash.yml             新增 Logstash 并替换 Agent 配置挂载
├── elastic-agent.logstash.yml       Agent 改为发送到 Logstash
├── compose-logstash.sh              固定加载两份 Compose 的命令入口
└── logstash/pipeline/requests.conf  Logstash 处理规则
```

`requests.conf` 分为三段：`input` 接收 Agent 的数据；`filter` 加工字段；`output` 写入 ES。完整规则直接打开该文件查看。

新的 Agent 输出配置为：

```yaml
outputs:
  default:
    type: logstash
    hosts:
      - logstash:5044
```

`5044` 是采集协议端口，不是网页地址。本实验不把它暴露到 Mac，只在 Compose 网络中通信。数据仍写入 `logs-demo.microservices-local`，Kibana 原来的 Data View 可以继续使用。

这里使用自定义 filestream 数据集，保留 Agent 原有字段；若以后接入依赖 ingest pipeline 的官方 Integration，需按该 Integration 的要求处理，不能直接套用任意字段改写规则。[Agent 输出说明](https://www.elastic.co/docs/reference/fleet/logstash-output)

## 4 下载并检查 Logstash

打开 Docker Desktop，在终端执行：

```bash
cd /Volumes/ulinsz/chatgpt-projects/size-elk/elastic-local
bash compose-logstash.sh config -q
bash compose-logstash.sh pull logstash
```

然后使用一次性容器检查管道语法，不启动业务链路：

```bash
bash compose-logstash.sh run --rm --no-deps logstash \
  bin/logstash --config.test_and_exit -f /usr/share/logstash/pipeline/requests.conf
```

看到 `Configuration OK` 且命令成功退出后，再继续。这个检查验证配置语法，不代表已经连接 ES 或收到了日志。

## 5 启动 Logstash 并切换 Agent

先启动 Logstash，现有 Agent 此时仍直连 ES：

```bash
bash compose-logstash.sh up -d logstash
bash compose-logstash.sh logs -f logstash
```

等待日志出现 `Pipeline started`、监听 5044 的信息，并确认没有持续报错。按 `Ctrl+C` 退出日志查看，不会停止容器。

再重新创建 Agent，让它读取新配置：

```bash
bash compose-logstash.sh up -d --no-deps --force-recreate elastic-agent
bash compose-logstash.sh ps
bash compose-logstash.sh logs --tail=80 elastic-agent logstash
```

这里是替换同一个 Agent 服务，沿用相同的日志目录、input ID 和 `agent-state` 卷。不要再额外启动另一个 Agent，否则可能重复采集。

已有日志通常不会重新采集，ES 中的历史记录也不会自动增加新字段。下面要重新发送请求验证。投递重试仍可能导致少量重复记录，不应把本实验视为严格 exactly-once 系统。

启用该方案后，日常启动使用 `bash compose-logstash.sh up -d`，避免单独使用基础 Compose 把 Agent 切回直连 ES。

## 6 发送成功与失败请求

确保网关、订单、库存三个服务已运行并注册到 Nacos。生成本轮唯一编号，连续测试 10 轮：

```bash
RUN_ID="ls-lab-$(date +%s)"
for i in {1..10}; do
  curl -sS -o /dev/null -w '商品1001 HTTP %{http_code}\n' \
    -H "X-Request-ID: ${RUN_ID}-ok-${i}" \
    http://localhost:8088/api/orders/1001
  curl -sS -o /dev/null -w '商品1003 HTTP %{http_code}\n' \
    -H "X-Request-ID: ${RUN_ID}-missing-${i}" \
    http://localhost:8088/api/orders/1003
done
printf '本轮查询前缀：%s\n' "$RUN_ID"
```

预期：1001 返回 200，1003 返回 404。多次请求也能让新日志文件超过采集指纹长度门槛。等待约 10～30 秒后查看 Kibana。

## 7 在 Kibana 证明 Logstash 生效

打开 Discover，选择原来的 Data View，时间范围设为最近 15 分钟。清除之前的 requestId、INFO 等筛选。

先查经过 Logstash 的日志：

```text
labels.processing_pipeline : "logstash_requests_v1"
```

展开最新一条网关完成日志，检查新增字段。也可在终端查询，不依赖 Kibana 展示配置：

```bash
curl -fsS 'http://localhost:9200/logs-demo.microservices-local/_search?pretty&size=1&sort=@timestamp:desc&q=labels.processing_pipeline:logstash_requests_v1'
```

再查失败的请求完成事件：

```text
labels.processing_pipeline : "logstash_requests_v1" AND event.action : "gateway_request_completed" AND event.outcome : "failure"
```

只查 404：

```text
labels.processing_pipeline : "logstash_requests_v1" AND http.response.status_code : 404
```

查看一次请求的全过程，例如把编号换成本轮输出的实际值：

```text
requestId : "ls-lab-实际编号-missing-1"
```

保留 `requestId` 查询时不要同时筛选 `event.action`，否则会隐藏订单和库存日志。

添加以下展示列：`@timestamp`、`service.name`、`message`、`requestId`、`http.response.status_code`、`event.outcome`、`duration_ms`、`tags`。

同一请求的原始本地 JSON 中没有 processing_pipeline 标记，而 ES 中有，就能直观看到 Logstash 对数据的加工。它不会回写修改本地日志文件。

## 8 可选做一张请求结果图

在 Kibana 的 Lens 中选择相同 Data View，筛选：

```text
labels.processing_pipeline : "logstash_requests_v1" AND event.action : "gateway_request_completed"
```

选择柱状图，横轴用 `@timestamp` 时间直方图，纵轴用记录数，按 `event.outcome` 拆分颜色，保存为“网关请求结果”。只统计完成日志，避免把一次请求的多条日志都算成请求次数。

若只看本轮，可以继续加 `requestId : "本轮完整前缀"*` 对应的前缀筛选，或使用界面字段过滤。本轮无重试、无其他请求时，预期为成功 10 次、失败 10 次；有重复投递时应按 requestId 去重再统计。

## 9 遇到问题和回退

| 现象 | 检查 |
|---|---|
| 旧日志没有新字段 | 正常；重新发送请求，查最新时间段 |
| 新日志也没有标记 | 确认用 compose-logstash.sh 重建过 Agent，检查新配置挂载 |
| Agent 提示 connection refused | 查看 Logstash 是否成功启动并监听 5044 |
| Logstash 输出 400 或字段类型冲突 | 查看 Logstash 错误；本实验使用 ECS 字段类型，已有自定义冲突 mapping 需另行处理 |
| 查不到任何日志 | 检查 Java 工作目录仍是 size-elk，日志是否写进项目 logs，确认时间范围 |
| 容器退出 137 | 检查 Docker 内存是否足够 |

统一排查命令：

```bash
bash compose-logstash.sh ps
bash compose-logstash.sh logs --tail=150 elastic-agent logstash
```

回到原来的 Agent 直连 ES 模式：

```bash
docker compose -f compose.yml up -d --no-deps --force-recreate elastic-agent
bash compose-logstash.sh stop logstash
```

这会保留 ES 数据和 Agent 采集进度。回退后新日志不再有 Logstash 添加的字段；历史加工记录仍在。不要执行 `down -v`。

该配置使用 Docker 内网明文连接，供本机实验。没有配置持久化队列，不能据此保证强制中断或故障情况下零数据丢失。

参考：[Logstash Agent 输入](https://www.elastic.co/docs/reference/logstash/plugins/plugins-inputs-elastic_agent)、[ES 数据流输出](https://www.elastic.co/docs/reference/logstash/plugins/plugins-outputs-elasticsearch)、[Logstash 9.5.3 镜像](https://www.docker.elastic.co/r/logstash/logstash:9.5.3)。
