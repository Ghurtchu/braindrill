# Distributed Remote Code Execution Engine

Drill your brain, because why the heck not.

Requirements for deploying locally:
- docker engine

Running locally:
- clone the project and navigate to the root directory
- `docker build -t braindrill .`
- `docker run -p 8080:8080 braindrill`

Example:
- sending `POST` request at `localhost:8080/lang/python`
- attaching `python` code to request body

![My Image](assets/python_example.png)

Supported programming languages:
- `JavaScript`: `localhost:8080/lang/javascript`
- `Python`: `localhost:8080/lang/python`

TODO:
- implement POC - ✅
- introduce http layer (pekko-http) - ✅
- add support for a few programming languages - ✅
- introduce local master and worker pekko actors - ✅
- introduce Pekko Cluster - ✅
- turn local master actor into load balancer node - ✅
- create 3 worker nodes - ✅
- make worker actors sharded on each node (e.g 25 actors on each node, awaiting tasks) - ✅
- run cluster within docker containers - ✅
- run code inside running worker node container - ✅
- get rid of `Futures` in actors, use `ctx.ask` or piping to self instead - ❌ 
- implement timeouts for long-running code - ❌
- add support for Java, C, C++, Go and others - ❌
- write load tests to check how system behaves during high load - ❌
- use otehr `pekko` libraries to make cluster bootstrapping and management flexible and configurable - ❌
- deploy a few pekko-http servers and enable `RoundRobin` load balancing - ❌
- wrap the cluster in k8s - ❌
- enable autoscaling  - ❌

Architecture Diagram:

![My Image](assets/diagram.png)