#!/usr/bin/groovy
package com.opsnow.valve.v8;

def debug() {
    sh """
        ls -al
        ls -al ~
    """
}

def prepare(name = "sample", version = "") {
    // image name
    this.name = name

    echo "# name: ${name}"

    set_version(version)

    this.cluster = ""
    this.namespace = ""
    this.sub_domain = ""
    this.values_home = ""
    this.image_repository = ""

    // this cluster
    load_variables()
}

def set_version(version = "") {
    // version
    if (!version) {
        date = (new Date()).format('yyyyMMdd-HHmm')
        version = "v0.0.${date}"
    }

    this.version = version

    echo "# version: ${version}"
}

def get_version() {
    if (!version) {
        throw new RuntimeException("No version")
    }
    echo "# version: ${version}"
    this.version
}

def set_values_home(values_home = "") {
    this.values_home = values_home

    echo "# values_home: ${values_home}"
}

def scan(source_lang = "") {
    this.source_lang = source_lang
    this.source_root = "."

    // language
    if (!source_lang || source_lang == "java") {
        scan_langusge("pom.xml", "java")
    }
    if (!source_lang || source_lang == "nodejs") {
        scan_langusge("package.json", "nodejs")
    }

    echo "# source_lang: ${this.source_lang}"
    echo "# source_root: ${this.source_root}"

    // chart
    make_chart()
}

def set_image_repository(use_ecr = true, account_id = "", region = "cn-north-1", ecr_repository = "opsnow") {
    if (use_ecr) {
        // get ecr uri
        if (!"${account_id}") {
            account_id = sh(script: "aws sts get-caller-identity | jq -r '.Account'", returnStdout: true).trim()
        }
        if (region == "cn-north-1") {
            // china region
            ecr_addr = "${account_id}.dkr.ecr.${region}.amazonaws.com.cn"
        } else {
            // global region
            ecr_addr = "${account_id}.dkr.ecr.${region}.amazonaws.com"
        }
        this.image_repository = "${ecr_addr}/${ecr_repository}"
    } else {
        this.image_repository = ""
    }
}

def load_variables() {
    // groovy variables
    sh """
        kubectl get secret groovy-variables -n default -o json | jq -r .data.groovy | base64 -d > ${home}/Variables.groovy && \
        cat ${home}/Variables.groovy | grep def
    """

    def val = load "${home}/Variables.groovy"

    this.slack_token = val.slack_token
    this.base_domain = val.base_domain

    if (val.cluster == "devops") {
        this.jenkins = val.jenkins
        this.chartmuseum = val.chartmuseum
        this.registry = val.registry
        this.sonarqube = val.sonarqube
        this.nexus = val.nexus
        this.harbor = val.harbor
        this.harbor_project = val.harbor_project
    }
}

def scan_langusge(target = "", target_lang = "") {
    def target_path = sh(script: "find . -name ${target} | head -1", returnStdout: true).trim()

    if (target_path) {
        def target_root = sh(script: "dirname ${target_path}", returnStdout: true).trim()

        if (target_root) {
            this.source_lang = target_lang
            this.source_root = target_root

            // maven mirror
            if (target_lang == "java") {
                if (this.nexus) {
                    def m2_home = "/home/jenkins/.m2"

                    def mirror_of  = "*,!nexus-public,!nexus-releases,!nexus-snapshots"
                    def mirror_url = "https://${nexus}/repository/maven-public/"
                    def mirror_xml = "<mirror><id>mirror</id><url>${mirror_url}</url><mirrorOf>${mirror_of}</mirrorOf></mirror>"

                    sh """
                        mkdir -p ${m2_home} && \
                        cp -f /root/.m2/settings.xml ${m2_home}/settings.xml && \
                        sed -i -e \"s|<!-- ### configured mirrors ### -->|${mirror_xml}|\" ${m2_home}/settings.xml
                    """
                }
            }
        }
    }
}

def env_cluster(cluster = "") {
    if (!cluster || "${cluster}" == "here") {
        // throw new RuntimeException("env_cluster:cluster is null.")
        return
    }

    sh """
        rm -rf ${home}/.aws && mkdir -p ${home}/.aws && \
        rm -rf ${home}/.kube && mkdir -p ${home}/.kube
    """

    this.cluster = cluster

    // check cluster secret
    count = sh(script: "kubectl get secret -n devops | grep 'kube-config-${cluster}' | wc -l", returnStdout: true).trim()
    if ("${count}" == "0") {
        echo "env_cluster:cluster is null."
        throw new RuntimeException("cluster is null.")
    }

    sh """
        kubectl get secret kube-config-${cluster} -n devops -o json | jq -r .data.aws | base64 -d > ${home}/aws_config
        kubectl get secret kube-config-${cluster} -n devops -o json | jq -r .data.text | base64 -d > ${home}/kube_config
        cp ${home}/aws_config ${home}/.aws/config && \
        cp ${home}/kube_config ${home}/.kube/config
    """

    // check current context
    count = sh(script: "kubectl config current-context | grep '${cluster}' | wc -l", returnStdout: true).trim()
    if ("${count}" == "0") {
        echo "env_cluster:current-context is not match."
        throw new RuntimeException("current-context is not match.")
    }

    // target cluster
    load_variables()
}

def env_aws(target = "") {
    if (!target || "${target}" == "here") {
        throw new RuntimeException("env_target:target is null.")
    }

    sh """
        rm -rf ${home}/.aws && mkdir -p ${home}/.aws
    """

    this.target = target

    // check target secret
    count = sh(script: "kubectl get secret -n devops | grep 'aws-config-${target}' | wc -l", returnStdout: true).trim()
    if ("${count}" == "0") {
        echo "env_target:target is null."
        throw new RuntimeException("target is null.")
    }

    sh """
        kubectl get secret aws-config-${target} -n devops -o json | jq -r .data.config | base64 -d > ${home}/aws_config
        kubectl get secret aws-config-${target} -n devops -o json | jq -r .data.credentials | base64 -d > ${home}/aws_credentials
        cp ${home}/aws_config ${home}/.aws/config
        cp ${home}/aws_credentials ${home}/.aws/credentials
    """
}

def env_namespace(namespace = "") {
    if (!namespace) {
        echo "env_namespace:namespace is null."
        throw new RuntimeException("namespace is null.")
    }

    this.namespace = namespace

    // check namespace
    count = sh(script: "kubectl get ns ${namespace} 2>&1 | grep Active | grep ${namespace} | wc -l", returnStdout: true).trim()
    if ("$count" == "0") {
        sh "kubectl create namespace ${namespace}"
    }
}

def env_config(type = "", name = "", namespace = "") {
    if (!type) {
        echo "env_config:type is null."
        throw new RuntimeException("type is null.")
    }
    if (!name) {
        echo "env_config:name is null."
        throw new RuntimeException("name is null.")
    }
    if (!namespace) {
        echo "env_config:namespace is null."
        throw new RuntimeException("namespace is null.")
    }

    // check config
    count = sh(script: "kubectl get ${type} -n ${namespace} | grep ${name} | wc -l", returnStdout: true).trim()
    if ("$count" == "0") {
        return "false"
    }

    return "true"

    // // md5sum
    // sum = sh(script: "kubectl get ${type} -n ${namespace} ${name} -o yaml | md5sum | awk '{printf \$1}'", returnStdout: true).trim()
    // return sum
}

def make_chart(path = "", latest = false) {
    if (!name) {
        echo "make_chart:name is null."
        throw new RuntimeException("name is null.")
    }
    if (latest) {
        echo "latest version scan"
        app_version = scan_images_version(name, true)
    } else {
        app_version = version
    }
    if (!version) {
        echo "make_chart:version is null."
        throw new RuntimeException("version is null.")

    }
    if (!path) {
        path = "charts/${name}"
    }

    if (!fileExists("${path}")) {
        echo "no file ${path}"
        return
    }

    dir("${path}") {
        sh """
            sed -i -e \"s/name: .*/name: ${name}/\" Chart.yaml && \
            sed -i -e \"s/version: .*/version: ${version}/\" Chart.yaml && \
            sed -i -e \"s/tag: .*/tag: ${app_version}/g\" values.yaml
        """

        if (registry) {
            sh "sed -i -e \"s|repository: .*|repository: ${registry}/${name}|\" values.yaml"
        }
    }
}

def build_chart(path = "") {
    if (!name) {
        echo "build_chart:name is null."
        throw new RuntimeException("name is null.")
    }
    if (!version) {
        echo "build_chart:version is null."
        throw new RuntimeException("version is null.")
    }
    if (!path) {
        path = "charts/${name}"
    }

    helm_init()

    // helm plugin
    count = sh(script: "helm plugin list | grep 'Push chart package' | wc -l", returnStdout: true).trim()
    if ("${count}" == "0") {
        sh """
            helm plugin install https://github.com/chartmuseum/helm-push && \
            helm plugin list
        """
    } else {
        sh """
            helm plugin update push && \
            helm plugin list
        """
    }

    // helm push
    dir("${path}") {
        sh "helm lint ."

        if (chartmuseum) {
            sh "helm push . chartmuseum"
        }
    }

    // helm repo
    sh """
        helm repo update && \
        helm search ${name}
    """
}

def build_image(harborcredential = "HarborAdmin") {
    if (!name) {
        echo "build_image:name is null."
        throw new RuntimeException("name is null.")
    }
    if (!version) {
        echo "build_image:version is null."
        throw new RuntimeException("version is null.")
    }

    sh "docker build -t ${registry}/${name}:${version} ."
    sh "docker push ${registry}/${name}:${version}"
    sh "docker image tag ${registry}/${name}:${version} ${harbor}/${harbor_project}/${name}:${version}"
    docker.withRegistry("https://${harbor}", "${harborcredential}") {
        sh "docker push ${harbor}/${harbor_project}/${name}:${version}"
    }
}

def helm_init() {
    sh """
        helm init --client-only && \
        helm version
    """

    if (chartmuseum) {
        sh "helm repo add chartmuseum https://${chartmuseum}"
    }

    sh """
        helm repo list && \
        helm repo update
    """
}

def apply(cluster = "", namespace = "", type = "", yaml = "") {
    if (!name) {
        echo "apply:name is null."
        throw new RuntimeException("name is null.")
    }
    if (!version) {
        echo "apply:version is null."
        throw new RuntimeException("version is null.")
    }
    if (!cluster) {
        echo "apply:cluster is null."
        throw new RuntimeException("cluster is null.")
    }
    if (!namespace) {
        echo "apply:namespace is null."
        throw new RuntimeException("namespace is null.")
    }

    if (!type) {
        type = "secret"
    }
    if (!yaml) {
        yaml = "${type}/${cluster}/${namespace}/${name}.yaml"
    }

    // yaml
    yaml_path = sh(script: "find . -name ${name}.yaml | grep '${yaml}' | head -1", returnStdout: true).trim()
    if (!yaml_path) {
        echo "apply:yaml_path is null."
        throw new RuntimeException("yaml_path is null.")
    }

    sh """
        sed -i -e \"s|name: REPLACE-ME|name: ${name}|\" ${yaml_path}
    """

    // cluster
    env_cluster(cluster)

    // namespace
    env_namespace(namespace)

    sh """
        kubectl apply -n ${namespace} -f ${yaml_path}
    """
}

def deploy_only(deploy_name = "", version = "", cluster = "", namespace = "", sub_domain = "", profile = "", values_path = "") {

    // env cluster
    env_cluster(cluster)

    // env namespace
    env_namespace(namespace)

    // helm init
    helm_init()

    sh """
        helm upgrade --install ${deploy_name} chartmuseum/${name} \
            --namespace ${namespace} --devel \
            --values ${values_path} \
            --set namespace=${namespace} \
            --set ingress.basedomain=${base_domain} \
            --set profile=${profile} 
    """

    sh """
        helm search ${name} && \
        helm history ${name}-${namespace} --max 10
    """
}

def deploy(cluster = "", namespace = "", sub_domain = "", profile = "", values_path = "") {
    if (!name) {
        echo "deploy:name is null."
        throw new RuntimeException("name is null.")
    }
    if (!version) {
        echo "deploy:version is null."
        throw new RuntimeException("version is null.")
    }
    if (!cluster) {
        echo "deploy:cluster is null."
        throw new RuntimeException("cluster is null.")
    }
    if (!namespace) {
        echo "deploy:namespace is null."
        throw new RuntimeException("namespace is null.")
    }
    if (!sub_domain) {
        sub_domain = "${name}-${namespace}"
    }
    if (!profile) {
        profile = namespace
    }

    // env cluster
    env_cluster(cluster)

    // env namespace
    env_namespace(namespace)

    // helm init
    helm_init()

    this.sub_domain = sub_domain

    // config configmap
    // configmap = env_config("configmap", name, namespace)
    // configmap_enabled = "false"
    // if (configmap_hash == "") {
    //     configmap_enabled = "true"
    // }

    // config secret
    // secret = env_config("secret", name, namespace)
    // secret_enabled = "false"
    // if (secret_hash != "") {
    //     secret_enabled = "true"
    // }

    // extra_values (format = --set KEY=VALUE)
    extra_values = ""

    // latest version
    if (version == "latest") {
        version = sh(script: "helm search chartmuseum/${name} | grep ${name} | head -1 | awk '{print \$2}'", returnStdout: true).trim()
        if (version == "") {
            echo "deploy:latest version is null."
            throw new RuntimeException("latest version is null.")
        }
    }

    // Keep latest pod count
    desired = sh(script: "kubectl get deploy -n ${namespace} | grep '${name} ' | head -1 | awk '{print \$3}'", returnStdout: true).trim()
    if (desired != "") {
        extra_values = "--set replicaCount=${desired}"
    }

    // deployment image repository
    if (image_repository) {
        extra_values = "${extra_values} --set image.repository=${image_repository}/${name}"
    }

    // values_path
    if (!values_path) {
        values_path = ""
        if (values_home) {
            count = sh(script: "ls ${values_home}/${name} | grep '${namespace}.yaml' | wc -l", returnStdout: true).trim()
            if ("${count}" == "0") {
                throw new RuntimeException("values_path not found.")
            } else {
                values_path = "${values_home}/${name}/${namespace}.yaml"
            }
        }
    }

    // app-version: https://github.com/helm/helm/pull/5492

    if (values_path) {

        // helm install
        sh """
            helm upgrade --install ${name}-${namespace} chartmuseum/${name} \
                --version ${version} --namespace ${namespace} --devel \
                --values ${values_path} \
                --set namespace=${namespace} \
                --set profile=${profile} \
                ${extra_values}
        """
        // --app-version ${version} \
        // --set configmap.enabled=${configmap} \
        // --set secret.enabled=${secret} \

    } else {

        // helm install
        sh """
            helm upgrade --install ${name}-${namespace} chartmuseum/${name} \
                --version ${version} --namespace ${namespace} --devel \
                --set fullnameOverride=${name} \
                --set ingress.subdomain=${sub_domain} \
                --set ingress.basedomain=${base_domain} \
                --set namespace=${namespace} \
                --set profile=${profile} \
                ${extra_values}
        """
        // --app-version ${version} \
        // --set configmap.enabled=${configmap} \
        // --set secret.enabled=${secret} \

    }

    sh """
        helm search ${name} && \
        helm history ${name}-${namespace} --max 10
    """
}

def scan_helm(cluster = "", namespace = "") {
    // must have cluster
    if (!cluster) {
        echo "remove:cluster is null."
        throw new RuntimeException("cluster is null.")
    }
    env_cluster(cluster)

    // admin can scan all images,
    // others can scan own images.
    if (!namespace) {
        list = sh(script: "helm ls | awk '{print \$1}'", returnStdout: true).trim()
    } else {
        list = sh(script: "helm ls --namespace ${namespace} | awk '{print \$1}'", returnStdout: true).trim()
    }
    list
}

def scan_images() {
    if (!chartmuseum) {
        load_variables()
    }
    list = sh(script: "curl -X GET https://${registry}/v2/_catalog | jq -r '.repositories[]'", returnStdout: true).trim()
    list
}

def scan_images_version(image_name = "", latest = false) {
    if (!chartmuseum) {
        load_variables()
    }
    if(latest) {
      list = sh(script: "curl -X GET https://${registry}/v2/${image_name}/tags/list | jq -r '.tags[]' | sort -r | head -n 1", returnStdout: true).trim()
    } else {
      list = sh(script: "curl -X GET https://${registry}/v2/${image_name}/tags/list | jq -r '.tags[]' | sort -r", returnStdout: true).trim()
    }
    list
}

def scan_charts() {
    if (!chartmuseum) {
        load_variables()
    }
    list = sh(script: "curl https://${chartmuseum}/api/charts | jq -r 'keys[]'", returnStdout: true).trim()
    list
}

def scan_charts_version(mychart = "", latest = false) {
    if (!chartmuseum) {
        load_variables()
    }
    if (latest) {
      list = sh(script: "curl https://${chartmuseum}/api/charts/${mychart} | jq -r '.[].version' | sort -r | head -n 1", returnStdout: true).trim()
    } else {
      list = sh(script: "curl https://${chartmuseum}/api/charts/${mychart} | jq -r '.[].version' | sort -r", returnStdout: true).trim()
    }
    list
}

def rollback(cluster = "", namespace = "", revision = "") {
    if (!name) {
        echo "remove:name is null."
        throw new RuntimeException("name is null.")
    }
    if (!cluster) {
        echo "remove:cluster is null."
        throw new RuntimeException("cluster is null.")
    }
    if (!namespace) {
        echo "remove:namespace is null."
        throw new RuntimeException("namespace is null.")
    }
    if (!revision) {
        revision = "0"
    }

    // env cluster
    env_cluster(cluster)

    // helm init
    helm_init()

    sh """
        helm search ${name} && \
        helm history ${name}-${namespace} --max 10
    """

    sh "helm rollback ${name}-${namespace} ${revision}"
}

def remove(cluster = "", namespace = "") {
    if (!name) {
        echo "remove:name is null."
        throw new RuntimeException("name is null.")
    }
    if (!cluster) {
        echo "remove:cluster is null."
        throw new RuntimeException("cluster is null.")
    }
    if (!namespace) {
        echo "remove:namespace is null."
        throw new RuntimeException("namespace is null.")
    }

    // env cluster
    env_cluster(cluster)

    // helm init
    helm_init()

    sh """
        helm search ${name} && \
        helm history ${name}-${namespace} --max 10
    """

    sh "helm delete --purge ${name}-${namespace}"
}

def get_source_root(source_root = "") {
    if (!source_root) {
        if (!this.source_root) {
            source_root = "."
        } else {
            source_root = this.source_root
        }
    }
    return source_root
}

def get_m2_settings() {
    if (this.nexus) {
        settings = "-s /home/jenkins/.m2/settings.xml"
    } else {
        settings = ""
    }
    return settings
}

def npm_build(source_root = "") {
    source_root = get_source_root(source_root)
    dir("${source_root}") {
        sh "npm run build"
    }
}

def npm_test(source_root = "") {
    source_root = get_source_root(source_root)
    dir("${source_root}") {
        sh "npm run test"
    }
}

def npm_sonar(source_root = "", sonarqube = "") {
    if (!sonarqube) {
        if (!this.sonarqube) {
            echo "npm_sonar:sonarqube is null."
            throw new RuntimeException("sonarqube is null.")
        }
        sonarqube = "https://${this.sonarqube}"
    }
    withCredentials([string(credentialsId: 'npm-sonar', variable: 'sonar_token')]){
      source_root = get_source_root(source_root)
      sh """
          sed -i -e \"s,SONARQUBE,${sonarqube},g\" package.json && \
          sed -i -e \"s/SONAR_TOKEN/${sonar_token}/g\" package.json
      """
      dir("${source_root}") {
        sh "npm run sonar"
      }
    }
}

def gradle_build(source_root = "") {
    source_root = get_source_root(source_root)
    dir("${source_root}") {
        sh "gradle task bootjar"
    }
}

def gradle_deploy(source_root = "") {
    source_root = get_source_root(source_root)
    dir("${source_root}") {
        sh "gradle task publish"
    }
}

def mvn_build(source_root = "") {
    source_root = get_source_root(source_root)
    dir("${source_root}") {
        settings = get_m2_settings()
        sh "mvn package ${settings} -DskipTests=true"
    }
}

def mvn_test(source_root = "") {
    source_root = get_source_root(source_root)
    dir("${source_root}") {
        settings = get_m2_settings()
        sh "mvn test ${settings}"
    }
}

def mvn_deploy(source_root = "") {
    source_root = get_source_root(source_root)
    dir("${source_root}") {
        settings = get_m2_settings()
        sh "mvn deploy ${settings} -DskipTests=true"
    }
}

def mvn_sonar(source_root = "", sonarqube = "") {
    //if (!sonar_token) {
    //  echo "sonar token is null. Check secret text 'sonar-token' in credentials"
    //  throw new RuntimeException("sonar token is null.")
    //}
    if (!sonarqube) {
        if (!this.sonarqube) {
            echo "mvn_sonar:sonarqube is null."
            throw new RuntimeException("sonarqube is null.")
        }
        sonarqube = "https://${this.sonarqube}"
    }
    withCredentials([string(credentialsId: 'sonar-token', variable: 'sonar_token')]){
      source_root = get_source_root(source_root)
      dir("${source_root}") {
          settings = get_m2_settings()
          if (!sonar_token) {
            sh "mvn sonar:sonar ${settings} -Dsonar.projectName='sample-spring-v8' -Dsonar.host.url=${sonarqube} -DskipTests=true"
          } else {
            sh "mvn sonar:sonar ${settings} -Dsonar.projectName='sample-spring-v8' -Dsonar.login=${sonar_token} -Dsonar.host.url=${sonarqube} -DskipTests=true"
          }
      }
    }
}

def failure(token = "", type = "") {
    if (!name) {
        echo "failure:name is null."
        throw new RuntimeException("name is null.")
    }
    if (slack_token) {
        if (!token) {
            token = slack_token
        } else if (token instanceof List) {
            token.add(slack_token)
        } else {
            token = [token, slack_token]
        }
    }
    slack(token, "danger", "${type} Failure", "`${name}` `${version}`", "${JOB_NAME} <${RUN_DISPLAY_URL}|#${BUILD_NUMBER}>")
}

def success(token = "", type = "") {
    if (!name) {
        echo "failure:name is null."
        throw new RuntimeException("name is null.")
    }
    if (!version) {
        echo "failure:version is null."
        throw new RuntimeException("version is null.")
    }
    if (cluster && sub_domain) {
        def link = "https://${sub_domain}.${base_domain}"
        slack(token, "good", "${type} Success", "`${name}` `${version}` :satellite: `${namespace}` :earth_asia: `${cluster}`", "${JOB_NAME} <${RUN_DISPLAY_URL}|#${BUILD_NUMBER}> : <${link}|${name}-${namespace}>")
    } else {
        slack(token, "good", "${type} Success", "`${name}` `${version}` :heavy_check_mark:", "${JOB_NAME} <${RUN_DISPLAY_URL}|#${BUILD_NUMBER}>")
    }
}

def proceed(token = "", type = "", namespace = "") {
    if (!name) {
        echo "proceed:name is null."
        throw new RuntimeException("name is null.")
    }
    if (!version) {
        echo "proceed:version is null."
        throw new RuntimeException("version is null.")
    }
    slack(token, "warning", "${type} Proceed?", "`${name}` `${version}` :rocket: `${namespace}`", "${JOB_NAME} <${RUN_DISPLAY_URL}|#${BUILD_NUMBER}>")
}

def slack(token = "", color = "", title = "", message = "", footer = "") {
    try {
        if (token) {
            if (token instanceof List) {
                for (item in token) {
                    send(item, color, title, message, footer)
                }
            } else {
                send(token, color, title, message, footer)
            }
        }
    } catch (ignored) {
    }
}

def send(token = "", color = "", title = "", message = "", footer = "") {
    try {
        if (token) {
            sh """
                curl -sL repo.opsnow.io/valve-ctl/slack | bash -s -- --token=\'${token}\' \
                --footer=\'$footer\' --footer_icon='https://jenkins.io/sites/default/files/jenkins_favicon.ico' \
                --color=\'${color}\' --title=\'${title}\' \'${message}\'
            """
        }
    } catch (ignored) {
    }
}

//-------------------------------------
// Terraform
//-------------------------------------

def terraform_init(cluster = "", path = "") {
    if (!cluster) {
        echo "failure:cluster is null."
        throw new RuntimeException("cluster is null.")
    }
    if (!path) {
        echo "failure:path is null."
        throw new RuntimeException("path is null.")
    }

    env_aws(cluster)

    dir("${path}") {
        if (fileExists(".terraform")) {
            sh """
                rm -rf .terraform
                terraform init -no-color
            """
        } else {
            sh """
                terraform init -no-color
            """
        }
    }
}

def terraform_check_changes(cluster = "", path = "") {
    terraform_init(cluster, path)

    dir("${path}") {
        sh """
            terraform plan -no-color
        """
        changed = sh (
            script: "terraform plan -no-color | grep Plan",
            returnStatus: true
        ) == 0

        if (!changed) {
            echo "No changes. Infrastructure is up-to-date."
            throw new RuntimeException("No changes. Infrastructure is up-to-date.")
        }
    }
}

def terraform_apply(cluster = "", path = "") {
    terraform_init(cluster, path)

    dir("${path}") {
        applied = sh (
            script: "terraform apply -auto-approve",
            returnStatus: true
        ) == 0

        if (!applied) {
            echo "Apply failed!"
            throw new RuntimeException("Apply failed!")
        }
    }
}

//-------------------------------------
// Pull Request for Version Update
//-------------------------------------

def checkout_pipeline(credentials_id="", git_url = "") {
    sshagent (credentials: [credentials_id]) {
        cloned = sh (
            script: "printenv; ssh-add -l; git clone ${git_url}",
            returnStatus: true
        ) == 0

        if (!cloned) {
            echo "Clone failed!"
            throw new RuntimeException("Clone failed!")
        }
    }
}

def create_pull_request(credentials_id="", path = "", site = "", profile = "", job = "", image = "") {
    if (!path) {
        echo "failure:path is null."
        throw new RuntimeException("path is null.")
    }
    if (!site) {
        echo "failure:site is null."
        throw new RuntimeException("site is null.")
    }
    if (!profile) {
        echo "failure:profile is null."
        throw new RuntimeException("profile is null.")
    }
    if (!job) {
        echo "failure:job is null."
        throw new RuntimeException("job is null.")
    }

    if (!image) {
        image = "${registry}/${name}:${version}"
    }

    dir("${path}") {
        sshagent (credentials: [credentials_id]) {
            created = sh (
                script: "printenv; ssh-add -l;  ./builder.sh ${site} ${profile} ${job} ${image}",
                returnStatus: true
            ) == 0

            if (!created) {
                echo "Version Update PR failed!"
                throw new RuntimeException("Version Update PR failed!")
            }
        }
    }
}


return this
