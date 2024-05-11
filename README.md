# Distributed Remote Code Execution Engine

Drill your brain, because why the heck not.

The goal of the project is to gain some practical experience using Pekko Cluster, containers, k8s and typed actor concurrency.

TODO:
- implement POC - ✅
- introduce http layer (pekko-http) - ✅
- add support for a few programming languages - ✅
- introduce local master and worker pekko actors - ✅
- create master actor (load balancer, pekko-http) - ❌
- create pekko cluster of worker nodes (let's say 3) - ❌
- enable `RoundRobin` load balancing - ❌
- make worker actors sharded on each node (e.g 100 actors on each node, awaiting tasks) - ❌

