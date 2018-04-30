# Introduction to message queues

# JMS - Java Message Service API

JMS gives good encapsulation for basic messaging needs. 

JMS is supported by large variety of brokers, which enables to change the underlying message broker without affecting client implementations.

# ActiveMQ Artemis

Lupapiste currently uses [ActiveMQ Artemis](https://activemq.apache.org/artemis/index.html) as message broker. It's a results of merging ActiveMQ and HornetQ into one product.

> The overall objective for working toward feature parity between ActiveMQ 5.x and Artemis is for Artemis to eventually become ActiveMQ 6.x.

Artemis itself is [protocol agnostic](https://activemq.apache.org/artemis/docs/latest/architecture.html) and doesn't actually know JMS, but JMS semantics are implemented as facede in client side.


# Development

Locally an embedded ActiveMQ Artemis broker is started, this can be controlled with `:embedded-broker` feature flag. See `artemis-server` namespace.

Alternatively you can fire up local instance by [installing Artemis](https://activemq.apache.org/artemis/download.html), and then setting JMS properties. See example in [local.properties](../resources/local.properties).

# TODOs

* JMS transactions
* Server side re-delivery delay

# ActiveMQ Artemis server FAQ

## Auto-create and auto-delete addresses?

By default Artemis server is provisioned with "auto-create" set to true. This means it will auto-create queue, when message is sent to it. This is good for dynamic queues.
On the contrary, also "auto-delete" feature is set to true by default. This means queue is deleted from broker when there are 0 consumers and 0 messages. In general this is fine too.

~~It seems it's not possible to retain dynamically created queues between server restarts. When server is restarted, client consumers don't receive messages anymore to queues they subscribed. 
Instead an exception is raised on server side when consumer re-connects to it with message "Queue X does not exists".~~ This might be a bug in Artemis UPDATE: yes it's a bug: https://issues.apache.org/jira/browse/ARTEMIS-1818.

Read more about config possibilities from [Artemis documentation](https://activemq.apache.org/artemis/docs/latest/address-model.html#automatic-addressqueue-management).

# JMS client FAQ

## Difference between persistent and non-persistent delivery?

Non-persistent deliveries are not saved on disk in the broker, while persistent messages are. More at [ActiveMQ site](http://activemq.apache.org/what-is-the-difference-between-persistent-and-non-persistent-delivery.html).

## Shared or individual connection/session per message?

This seems to be source of some debate. 

Majority of sources (eg. [ActiveMQ](http://activemq.apache.org/how-do-i-use-jms-efficiently.html)) suggest to share connections/sessions as long as possible.

Some others (eg. Spring JMS template) have implementations where connections and sessions are created and closed for each message. 
For example there is a [comment in Stackoverflow](https://stackoverflow.com/a/24494739) supporting this kind of approach.

There are some considerations on [ActiveMQ site](http://activemq.apache.org/spring-support.html), about using pooling connections to overcome some limitations with this JMS template approach.

> Note: while the PooledConnectionFactory does allow the creation of a collection of active consumers, it does not 'pool' consumers. 
> Pooling makes sense for connections, sessions and producers, which can be seldom-used resources, are expensive to create and can remain idle a minimal cost. 
> Consumers, on the other hand, are usually just created at startup and left going, handling incoming messages as they come.   
  
When reusing connections there are couple of things to consider:

1. During a JMS Session, only one action can be done at a time or else you will face a exception. 
For example if you share session with producer and consumer, and you produce messages in a loop, consumer side will throw exception because same session is used simultaneously both to produce and consume.
Solution to this problem is to separate consumer and producer sessions.
2. If remote broker connection is disconnected (for example network problems or boot), ~~all the sessions get disconnected. It seems those sessions can't be restarted, but instead new ones should be created upon reconnect.~~
EDIT: new infromation, previously described disconnection of sessions happens if the JMS connection factory is not configured for reconnect. But by default we initialize ActiveMQJMSConnectionFactory in a way that it will automatically reconnect when broker is back online. See `jms.clj`.

## What happens if we send message to broker that's offline?

When connection is lost, the Artemis JMS Client issues us warning and starts reconnecting:
```
WARN 2018-04-17 14:50:46,756 [org.apache.activemq.artemis.core.client] - <AMQ212037: Connection failure has been detected: AMQ119015: The connection was disconnected because of server shutdown [code=DISCONNECTED]>
```

If during lost connection we try to send a message to producer, it will block until timeout is reached:
```clojure
; foo1 is producer created by `create-producer`
(foo1 "test")
; ...blocks for 30 secs...
; => ActiveMQConnectionTimedOutException AMQ119014: Timed out after waiting 30,000 ms for response when sending packet 71  org.apache.activemq.artemis.core.protocol.core.impl.ChannelImpl.sendBlocking (ChannelImpl.java:415)
```

So the client should have some kind of recovery for these situations.
