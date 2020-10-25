/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */
 package example

 import java.util
 import java.util.Collections

 import akka.actor.typed.Behavior
 import akka.actor.typed.scaladsl.ActorContext
 import akka.actor.typed.scaladsl.Behaviors
 import example.crd.PodSet
 import io.fabric8.kubernetes.api.model.Pod
 import io.fabric8.kubernetes.api.model.PodBuilder
 import io.fabric8.kubernetes.client.KubernetesClient
 import io.fabric8.kubernetes.client.informers.cache.Lister

 object Reconciler {

   sealed trait Command
   case class AddPodSet(key: String) extends Command

   def apply(client: KubernetesClient, podSetLister: Lister[PodSet], podLister: Lister[Pod]): Behavior[Command] = {
     Behaviors.setup[Command] { context =>
         new Reconciler(context, client, podSetLister, podLister).behavior()
       }
   }
 }


 class Reconciler(context: ActorContext[Reconciler.Command], client: KubernetesClient, podSetLister: Lister[PodSet], podLister: Lister[Pod]) {
   import Reconciler._

   def behavior(): Behavior[Command] = {
     Behaviors.receiveMessage {
       case AddPodSet(key) =>
         context.log.info("Got {}", key)
         if (key.isEmpty || (!key.contains("/")))
           context.log.warn("invalid resource key: {}", key)
         // Get the PodSet resource's name from key which is in format namespace/name
         val name = key.split('/')(1)
         val podSet = podSetLister.get(key.split('/')(1))
         if (podSet == null) {
           context.log.error("PodSet {} in workqueue no longer exists", name)
         } else {
           reconcile(podSet)
         }
       Behaviors.same
     }
   }


   private def reconcile(podSet: PodSet): Unit = {
     context.log.info("reconcile {}", podSet)
     // FIXME some blocking operations here.
     val pods = podCountByLabel("app", podSet.getMetadata.getName)
     if (pods.isEmpty) {
       createPods(podSet.spec.replicas, podSet)
     } else {
       val existingPods = pods.size
       // Compare it with desired state i.e spec.replicas
       // if less then spin up pods
       if (existingPods < podSet.spec.replicas)
         createPods(podSet.spec.replicas - existingPods, podSet)
       // If more pods then delete the pods
       var diff = existingPods - podSet.spec.replicas

       while (diff > 0) {
         val podName = pods.remove(0)
         client.pods.inNamespace(podSet.getMetadata.getNamespace).withName(podName).delete
         diff -= 1
       }
     }
   }

   private def createPods(numberOfPods: Int, podSet: PodSet): Unit = {
     (0 until numberOfPods).foreach { _ =>
       val pod = createNewPod(podSet)
       client.pods.inNamespace(podSet.getMetadata.getNamespace).create(pod)
     }
   }

   private def createNewPod(podSet: PodSet) =
     new PodBuilder().withNewMetadata
       .withGenerateName(podSet.getMetadata.getName + "-pod")
       .withNamespace(podSet.getMetadata.getNamespace)
       .withLabels(Collections.singletonMap("app", podSet.getMetadata.getName))
       .addNewOwnerReference()
       .withController(true)
       .withKind("PodSet")
       .withApiVersion("demo.k8s.io/v1alpha1")
       .withName(podSet.getMetadata.getName)
       .withNewUid(podSet.getMetadata.getUid)
       .endOwnerReference
       .endMetadata
       .withNewSpec
       .addNewContainer()
       .withName("busybox")
       .withImage("busybox")
       .withCommand("sleep", "3600")
       .endContainer
       .endSpec
       .build

   private def podCountByLabel(label: String, podSetName: String) = {
     val podNames = new util.ArrayList[String]
     val pods = podLister.list
     import scala.jdk.CollectionConverters._
     for (pod <- pods.asScala) {
       if (pod.getMetadata.getLabels.entrySet.contains(new util.AbstractMap.SimpleEntry(label, podSetName)))
         if (pod.getStatus.getPhase.equals("Running") || pod.getStatus.getPhase.equals("Pending"))
           podNames.add(pod.getMetadata.getName)
     }
     context.log.info("count: {}", podNames.size)
     podNames
   }

}
