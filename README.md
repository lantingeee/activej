[![Maven Central](https://img.shields.io/maven-central/v/io.activej/activej)](https://mvnrepository.com/artifact/io.activej)
[![GitHub](https://img.shields.io/github/license/activej/activej)](https://github.com/activej/activej/blob/master/LICENSE)

## Introduction

[ActiveJ](https://activej.io) is a full-featured alternative Java platform built from the ground up as a replacement of Spring, 
Spark, Quarkus, Micronauts, and other solutions. It is minimalistic, boilerplate-free, and incomparably faster, which is proven by benchmarks.
ActiveJ has minimum third-party dependencies, yet features full stack of technologies with efficient async programming model and powerful 
DI library [ActiveInject](https://inject.activej.io)

## Quick start

Insert this snippet to your terminal...

```
mvn archetype:generate -DarchetypeGroupId=io.activej -DarchetypeArtifactId=archetype-http -DarchetypeVersion=1.1
```

... and open the project in your favourite IDE. Then, build the application and run it. Open your browser on [localhost:8080](http://localhost:8080) 
to see the "Hello World" message. 

#### Full-featured embedded web application server with Dependency Injection:
```java
public final class HttpHelloWorldExample extends HttpServerLauncher { 
    @Provides
    AsyncServlet servlet() { 
        return request -> HttpResponse.ok200().withPlainText("Hello, World!");
    }

    public static void main(String[] args) throws Exception {
        Launcher launcher = new HttpHelloWorldExample();
        launcher.launch(args); 
    }
}
```
- *The JAR file size of the example is only 1.4 MB. In comparison, minimal Spring web app size is 17 MB*.
- *This example cold start time is 0.65 sec.*
- *This example uses [ActiveInject](https://inject.activej.io) DI library which is 5.5 times faster than Guice and 100s times faster than Spring.*

To learn more about ActiveJ, please visit https://activej.io or follow our 5-minute getting-started 
[guide](https://activej.io/tutorials/getting-started). 

## Repository Structure
This repository contains [ActiveJ](https://activej.io) platform components along with helper ActiveJ libraries:
* [ActiveInject](https://inject.activej.io) - `core-inject` module;
* [ActiveSerializer](https://serializer.activej.io) - `core-serializer` module;
* [ActiveCodegen](https://codegen.activej.io) - `core-codegen` module;
* [ActiveSpecializer](https://specializer.activej.io) - `core-specializer` module.
* [ActiveRPC](https://rpc.activej.io) - `cloud-rpc` module;
* [ActiveFS](https://fs.activej.io) - `cloud-fs` module;

You can find examples of using ActiveJ platform and all the ActiveJ libraries in [`examples`](https://github.com/activej/activej/tree/master/examples) module.
