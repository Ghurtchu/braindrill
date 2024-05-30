# Distributed Remote Code Execution Engine

Send code, we will run it :)

Video demo: https://www.youtube.com/watch?v=sMlJC7Kr330

There are multiple ways to execute the client code:
- run it on bare machine
- run it within the running docker container that also wraps the running app itself
- run it in a "sibling" short-lived containers (DooD - docker out of docker)

I tried it all and decided to use the DooD approach by mounting docker.sock to app containers so that docker engine can be accesed within the running container.

Requirements for deploying locally:
- docker engine

Running locally (startup may be slow for the first time since it needs to pull a few docker images):
- clone the project and navigate to the root directory
- start the docker engine
- `chmod +x deploy.sh`
- `./deploy.sh`

In case you change code and want to run the new version you should execute:
- `./deploy.sh rebuild`

Example:
- sending `POST` request at `localhost:8080/lang/python`
- attaching `python` code to request body

![My Image](assets/python_example.png)

Supported programming languages:
- `Java 17`: `localhost:8080/lang/java`
- `Python 3`: `localhost:8080/lang/python`
- `JavaScript / Node 16`: `localhost:8080/lang/javascript`

Simple code snippets for testing:

- `Java`
```java
public class BrainDrill {
    public static void main(String[] args) {
        System.out.println("drill my brain");
    }
}
```

- `Python`
```python
print("drill my brain") 
```

- `JavaScript`:
```javascript
console.log("drill my brain");
```

DONE:
- implement POC - ✅
- introduce http layer (pekko-http) - ✅
- introduce local master and worker pekko actors - ✅
- introduce Pekko Cluster - ✅
- turn local master actor into load balancer node - ✅
- create 3 worker nodes - ✅
- distribute worker actors equally on each node (e.g 25 actors on each node, awaiting tasks) - ✅
- run cluster via docker-compose - ✅ 
- implement timeouts & cleanup for long-running code, limit CPU and RAM, add secure layer - ✅
- write basic load test to check how system behaves during high load - ✅
- perform aggregation statistics in load test - ✅

TODO:
- add support for C, Go, Rust and others - ❌
- use other `pekko` libraries to make cluster bootstrapping and management flexible and configurable - ❌
- wrap the cluster in k8s and enable autoscaling - ❌
- deploy two separate clusters and enable `RoundRobin` load balancing via k8s - ❌

Architecture Diagram:

![My Image](assets/diagram.png)
