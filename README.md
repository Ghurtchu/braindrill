# Distributed Remote Code Execution Engine

Drill your brain, because why the heck not.

The goal of the project is to gain some practical experience using Pekko Cluster, containers, k8s and typed actor concurrency.

TODO:
- implement POC - ✅
- introduce http layer (pekko-http) - ✅
- add support for a few programming languages - ✅
- introduce local master and worker pekko actors - ✅
- introduce Pekko Cluster - ✅
- turn local master actor into load balancer node - ✅
- create 3 worker nodes - ✅
- make worker actors sharded on each node (e.g 25 actors on each node, awaiting tasks) - ✅
- enable autoscaling  - ❌
- enable `RoundRobin` load balancing - ❌
- wrap the cluster in k8s - ❌

