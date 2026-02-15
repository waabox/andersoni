package org.waabox.andersoni.admin.config;

import java.time.Duration;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.Config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configures the Kubernetes API client.
 *
 * <p>Auto-detects in-cluster credentials when deployed inside a pod, or falls
 * back to the local kubeconfig for development.
 *
 * @author waabox(waabox[at]gmail[dot]com)
 */
@Configuration
public class KubernetesConfig {

  /** Creates the Kubernetes API client bean.
   *
   * <p>Uses {@link Config#defaultClient()} which checks, in order:
   * <ol>
   *   <li>In-cluster service account token</li>
   *   <li>KUBECONFIG environment variable</li>
   *   <li>~/.kube/config</li>
   * </ol>
   *
   * @return a configured {@link ApiClient}, never null.
   *
   * @throws Exception if the client cannot be created.
   */
  @Bean
  public ApiClient kubernetesApiClient() throws Exception {
    ApiClient client = Config.defaultClient();
    client.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
    return client;
  }
}
