# Kubernetes Operator example

Implemented with [Fabric8 kubernetes-client](https://github.com/fabric8io/kubernetes-client)

Ported to Scala from https://developers.redhat.com/blog/2019/10/07/write-a-simple-kubernetes-operator-in-java-using-the-fabric8-kubernetes-client/  

```
# create the CRD
kubectl apply -f kubernetes/pod-set-crd.yml

# run operator
sbt "runMain example.PodSetOperatorMain"

# create CR
kubectl apply -f kubernetes/pod-set-cr.yml

kubectl get pods

# change CR and apply again...

# delete pods with
kubectl delete podsets.demo.k8s.io/example-podset
```
