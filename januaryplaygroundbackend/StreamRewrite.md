# Stream Rewrite

# 2025.11.08

Starting off, it makes a lot more sense to provide a properties file rather than compile a JAR with one. So that gives
us the following `applications.properties`, used with the arguments:
`/Users/iain/code/januraryplayground/resources/application.properties insecure`

```properties
database=/Users/iain/code/januraryplayground/app.sqlite
bootstrap.servers=santa-cruz-kafka-0.iainschmitt.com:9093
security.protocol=SSL
ssl.keystore.type=JKS
ssl.keystore.location=/Users/iain/code/januraryplayground/januaryplaygroundbackend/app/src/main/resources/client/client.keystore.jks
ssl.keystore.password=*****
ssl.key.password=*****
ssl.truststore.type=JKS
ssl.truststore.location=/Users/iain/code/januraryplayground/januaryplaygroundbackend/app/src/main/resources/client/client.truststore.jks
ssl.truststore.password=*****
ssl.protocol=TLSv1.2
ssl.enabled.protocols=TLSv1.2
ssl.endpoint.identification.algorithm=
```

The following topics will be used. I really don't like the idea of environment-specific topics, but I also do not 
care for having to run Kafka locally.

```
test-xchng-notification-rules
test-xchng-order-records
test-xchng-position-records
test-xchng-session
test-xchng-ticker
test-xchng-user
```