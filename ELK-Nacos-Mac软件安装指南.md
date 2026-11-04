# v-1.2 ELK 与 Elastic Agent 搭建入口

当前分支默认采用：日志文件 → Elastic Agent → Logstash → Elasticsearch → Kibana。

完整搭建、配置说明和测试步骤见 [Logstash 请求日志加工实验](Logstash请求日志加工实验.md)，从第 3 节开始搭建。

运行配置位于 `elastic-local/compose.yml`，使用标准 `docker compose` 命令即可，不需要额外脚本或第二份 Compose。

未引入 Logstash 的安装指南和配置保存在 `v-1.1` 分支。
