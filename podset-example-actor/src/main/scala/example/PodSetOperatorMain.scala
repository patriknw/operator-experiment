/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */
package example

import akka.actor.typed.ActorSystem
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import example.crd.DoneablePodSet
import example.crd.PodSet
import example.crd.PodSetList
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodList
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionBuilder
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionVersionBuilder
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext
import io.fabric8.kubernetes.client.informers.cache.Lister
import io.fabric8.kubernetes.client.utils.Serialization
import org.slf4j.LoggerFactory

object PodSetOperatorMain {
  private val logger =
    LoggerFactory.getLogger("example.PodSetOperatorMain")

  def main(args: Array[String]): Unit = {
    Serialization.jsonMapper().registerModule(DefaultScalaModule)

    val client = new DefaultKubernetesClient
    try {
      val namespace = client.getNamespace match {
        case null =>
          logger.info("No namespace found via config, assuming default.")
          "default"
        case ns => ns
      }
      logger.info("Using namespace : {}", namespace)

      // FIXME this is not used
      val podSetCustomResourceDefinition = new CustomResourceDefinitionBuilder()
        .withNewMetadata()
        .withName("podsets.demo.k8s.io")
        .endMetadata()
        .withNewSpec()
        .withGroup("demo.k8s.io")
        .withVersions(new CustomResourceDefinitionVersionBuilder().withName("v1alpha1").build())
        .withNewNames()
        .withKind("PodSet")
        .withPlural("podsets")
        .endNames()
        .withScope("Namespaced")
        .endSpec()
        .build()

      val podSetCustomResourceDefinitionContext =
        new CustomResourceDefinitionContext.Builder()
          .withVersion("v1alpha1")
          .withScope("Namespaced")
          .withGroup("demo.k8s.io")
          .withPlural("podsets")
          .build()


      client.customResources(
        podSetCustomResourceDefinitionContext,
        classOf[PodSet],
        classOf[PodSetList],
        classOf[DoneablePodSet])

      val informerFactory = client.informers()

      val podInformer =
        informerFactory.sharedIndexInformerFor(classOf[Pod], classOf[PodList], 10 * 60 * 1000)
      val podSetInformer =
        informerFactory.sharedIndexInformerForCustomResource(
          podSetCustomResourceDefinitionContext,
          classOf[PodSet],
          classOf[PodSetList],
          10 * 60 * 1000)

      val podSetLister: Lister[PodSet] = new Lister(podSetInformer.getIndexer, namespace)
      val podLister: Lister[Pod] = new Lister(podInformer.getIndexer, namespace)

      informerFactory.startAllRegisteredInformers()
      informerFactory.addSharedInformerEventListener(exception =>
        logger.error("Exception occurred, but caught", exception))

      val system = ActorSystem(Reconciler(client, podSetLister, podLister), "PodSetOperator")
      // FIXME error handling, if ActorSystem is terminated the JVM should exit

      PodSetInformer.create(podInformer, podSetInformer, podSetLister, system)

    } catch {
      case exc: KubernetesClientException =>
        logger.error("Kubernetes Client Exception : {}", exc.getMessage)
        throw exc
    }
  }

}
