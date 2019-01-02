#!/usr/bin/groovy
package com.opsnow.valve;

def prepare(namespace = "devops") {
    sh """
        kubectl get secret groovy-variables -n $namespace -o json | jq -r .data.groovy | base64 -d > $home/Variables.groovy && \
        cat $home/Variables.groovy | grep def
    """

    def val = load "$home/Variables.groovy"

    this.base_domain = val.base_domain
    this.jenkins = "${val.jenkins}"
    this.chartmuseum = "${val.chartmuseum}"
    this.registry = "${val.registry}"
    this.sonarqube = "${val.sonarqube}"
    this.nexus = "${val.nexus}"
    this.slack_token = "${val.slack_token}"
}

def scan(name = "sample", branch = "master", source_lang = "", version = "") {
    this.name = name
    this.branch = branch
    this.source_lang = source_lang
    this.source_root = "."

    // version
    if (!version) {
        date = (new Date()).format('yyyyMMdd-HHmm')

        if (branch == "master") {
            version = "v0.1.0-$date"
        } else {
            version = "v0.0.1-$date"
        }
    }

    this.version = version
    echo "# version: $version"

    // language
    if (!this.source_lang || this.source_lang == "java") {
        scan_langusge("pom.xml", "java")
    }
    if (!this.source_lang || this.source_lang == "nodejs") {
        scan_langusge("package.json", "nodejs")
    }

    sh """
        echo "# source_lang: $source_lang" && \
        echo "# source_root: $source_root"
    """

    // chart
    make_chart(name, version)
}

def scan_langusge(target = "", source_lang = "") {
    def target_path = sh(script: "find . -name $target | head -1", returnStdout: true).trim()

    if (target_path) {
        def source_root = sh(script: "dirname $target_path", returnStdout: true).trim()

        if (source_root) {
            this.source_lang = source_lang
            this.source_root = source_root

            // maven mirror
            if (source_lang == "java") {
                // replace this.version
                // dir(source_root) {
                //     sh "sed -i -e \"s|(<this.version>)(.*)(</this.version>)|\1${this.version}\3|\" pom.xml | true"
                // }

                if (this.nexus) {
                    def m2_home = "/home/jenkins/.m2"

                    def mirror_of  = "*,!nexus-public,!nexus-releases,!nexus-snapshots"
                    def mirror_url = "https://${this.nexus}/repository/maven-public/"
                    def mirror_xml = "<mirror><id>mirror</id><url>${mirror_url}</url><mirrorOf>${mirror_of}</mirrorOf></mirror>"

                    sh """
                        mkdir -p $m2_home && \
                        cp -f /root/.m2/settings.xml $m2_home/settings.xml && \
                        sed -i -e \"s|<!-- ### configured mirrors ### -->|${mirror_xml}|\" $m2_home/settings.xml
                    """
                }
            }
        }
    }
}

def env_cluster(cluster = "", namespace = "devops") {
    if (!cluster) {
        // throw new RuntimeException("env_cluster:cluster is null.")
        return
    }

    // check cluster secret
    count = sh(script: "kubectl get secret -n $namespace | grep 'kube-config-$cluster' | wc -l", returnStdout: true).trim()
    if ("$count" == "0") {
        throw new RuntimeException("cluster is null.")
    }

    sh """
        mkdir -p $home/.kube && \
        kubectl get secret kube-config-$cluster -n $namespace -o json | jq -r .data.text | base64 -d > $home/.kube/config && \
        kubectl config current-context
    """

    // check current context
    count = sh(script: "kubectl config current-context | grep '$cluster' | wc -l", returnStdout: true).trim()
    if ("$count" == "0") {
        throw new RuntimeException("current-context is not match.")
    }
}

def env_namespace(namespace = "") {
    if (!namespace) {
        echo "env_namespace:namespace is null."
        throw new RuntimeException("namespace is null.")
    }

    // check namespace
    count = sh(script: "kubectl get ns $namespace 2>&1 | grep Active | grep $namespace | wc -l", returnStdout: true).trim()
    if ("$count" == "0") {
        sh "kubectl create namespace $namespace"
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
    count = sh(script: "kubectl get $type -n $namespace | grep \"$name-$namespace \" | wc -l", returnStdout: true).trim()
    if ("$count" == "0") {
        return "false"
    }
    return "true"
}

def apply_config(type = "", name = "", namespace = "", cluster = "", yaml = "") {
    if (!type) {
        echo "apply_config:type is null."
        throw new RuntimeException("type is null.")
    }
    if (!name) {
        echo "apply_config:name is null."
        throw new RuntimeException("name is null.")
    }
    if (!namespace) {
        echo "apply_config:namespace is null."
        throw new RuntimeException("namespace is null.")
    }
    if (!cluster) {
        echo "apply_config:cluster is null."
        throw new RuntimeException("cluster is null.")
    }

    // cluster
    env_cluster(cluster)

    // namespace
    env_namespace(namespace)

    // yaml
    if (!yaml) {
        if (cluster) {
            yaml = sh(script: "find . -name ${name}.yaml | grep $type/$cluster/$namespace/${name}.yaml | head -1", returnStdout: true).trim()
        } else {
            yaml = sh(script: "find . -name ${name}.yaml | grep $type/$namespace/${name}.yaml | head -1", returnStdout: true).trim()
        }
        if (!yaml) {
            throw new RuntimeException("yaml is null.")
        }
    }

    sh """
        sed -i -e \"s|name: REPLACE-ME|name: $name-$namespace|\" $yaml && \
        kubectl apply -n $namespace -f $yaml
    """

    if (!path) {
        sh "kubectl describe $type $name-$namespace -n $namespace"
    }
}

def make_chart(name = "", version = "") {
    if (!name) {
        echo "make_chart:name is null."
        throw new RuntimeException("name is null.")
    }
    if (!version) {
        echo "make_chart:version is null."
        throw new RuntimeException("version is null.")
    }

    if (!fileExists("charts/$name")) {
        return
    }

    dir("charts/$name") {
        sh """
            sed -i -e \"s/name: .*/name: $name/\" Chart.yaml && \
            sed -i -e \"s/version: .*/version: $version/\" Chart.yaml && \
            sed -i -e \"s/tag: .*/tag: $version/g\" values.yaml
        """

        if (registry) {
            sh "sed -i -e \"s|repository: .*|repository: $registry/$name|\" values.yaml"
        }
    }
}

def build_chart(name = "", version = "") {
    if (!name) {
        echo "build_chart:name is null."
        throw new RuntimeException("name is null.")
    }
    if (!version) {
        echo "build_chart:version is null."
        throw new RuntimeException("version is null.")
    }

    helm_init()

    // helm plugin
    count = sh(script: "helm plugin list | grep 'Push chart package' | wc -l", returnStdout: true).trim()
    if ("$count" == "0") {
        sh """
            helm plugin install https://github.com/chartmuseum/helm-push && \
            helm plugin list
        """
    }

    // helm push
    dir("charts/$name") {
        sh "helm lint ."

        if (chartmuseum) {
            sh "helm push . chartmuseum"
        }
    }

    // helm repo
    sh """
        helm repo update && \
        helm search $name
    """
}

def build_image(name = "", version = "") {
    if (!name) {
        echo "build_image:name is null."
        throw new RuntimeException("name is null.")
    }
    if (!version) {
        echo "build_image:version is null."
        throw new RuntimeException("version is null.")
    }

    sh "docker build -t $registry/$name:$version ."
    sh "docker push $registry/$name:$version"
}

def helm_init() {
    sh """
        helm init --upgrade && \
        helm version
    """

    if (chartmuseum) {
        sh "helm repo add chartmuseum https://$chartmuseum"
    }

    sh """
        helm repo list && \
        helm repo update
    """
}

def helm_install(name = "", version = "", namespace = "", base_domain = "", cluster = "", profile = "") {
    if (!name) {
        echo "helm_install:name is null."
        throw new RuntimeException("name is null.")
    }
    if (!version) {
        echo "helm_install:version is null."
        throw new RuntimeException("version is null.")
    }
    if (!namespace) {
        echo "helm_install:namespace is null."
        throw new RuntimeException("namespace is null.")
    }
    if (!base_domain) {
        echo "helm_install:base_domain is null."
        throw new RuntimeException("base_domain is null.")
    }

    // profile
    if (!profile) {
        profile = "$namespace"
    }

    // env cluster
    env_cluster(cluster)

    // env namespace
    env_namespace(namespace)

    // config (secret, configmap)
    configmap = env_config("configmap", name, namespace)
    secret = env_config("secret", name, namespace)

    // helm init
    helm_init()

    if (version == "latest") {
        version = sh(script: "helm search chartmuseum/$name | grep $name | head -1 | awk '{print \$2}'", returnStdout: true).trim()
        if (!version) {
            echo "helm_install:version is null."
            throw new RuntimeException("version is null.")
        }
    }

    desired = sh(script: "kubectl get deploy -n $namespace | grep \"$name-$namespace \" | head -1 | awk '{print \$2}'", returnStdout: true).trim()
    if (desired == "") {
        desired = 1
    }

    sh """
        helm upgrade --install $name-$namespace chartmuseum/$name \
                     --version $version --namespace $namespace --devel \
                     --set fullnameOverride=$name-$namespace \
                     --set ingress.basedomain=$base_domain \
                     --set configmap.enabled=$configmap \
                     --set secret.enabled=$secret \
                     --set replicaCount=$desired \
                     --set profile=$profile
    """

    sh """
        helm search $name && \
        helm history $name-$namespace --max 10
    """
}

def helm_delete(name = "", namespace = "", cluster = "") {
    if (!name) {
        echo "helm_delete:name is null."
        throw new RuntimeException("name is null.")
    }
    if (!namespace) {
        echo "helm_delete:namespace is null."
        throw new RuntimeException("namespace is null.")
    }

    // env cluster
    env_cluster(cluster)

    // helm init
    helm_init()

    sh """
        helm search $name && \
        helm history $name-$namespace --max 10
    """

    sh "helm delete --purge $name-$namespace"
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
    else {
        settings = ""
    }
    return settings
}

def npm_build(source_root = "") {
    source_root = get_source_root(source_root)
    dir("$source_root") {
        sh "npm run build"
    }
}

def mvn_build(source_root = "") {
    source_root = get_source_root(source_root)
    dir("$source_root") {
        settings = get_m2_settings()
        sh "mvn package $settings -DskipTests=true"
    }
}

def mvn_test(source_root = "") {
    source_root = get_source_root(source_root)
    dir("$source_root") {
        settings = get_m2_settings()
        sh "mvn test $settings"
    }
}

def mvn_deploy(source_root = "") {
    source_root = get_source_root(source_root)
    dir("$source_root") {
        settings = get_m2_settings()
        sh "mvn deploy $settings -DskipTests=true"
    }
}

def mvn_sonar(source_root = "", sonarqube = "") {
    if (!sonarqube) {
        if (!this.sonarqube) {
            echo "mvn_sonar:sonarqube is null."
            throw new RuntimeException("sonarqube is null.")
        }
        sonarqube = this.sonarqube
    }
    source_root = get_source_root(source_root)
    dir("$source_root") {
        settings = get_m2_settings()
        sh "mvn sonar:sonar $settings -Dsonar.host.url=$sonarqube -DskipTests=true"
    }
}

def failure(token = "", type = "", name = "", version = "") {
    slack("$token", "danger", "$type Failure", "`$name`", "$JOB_NAME <$RUN_DISPLAY_URL|#$BUILD_NUMBER>")
}

def success(token = "", type = "", name = "", version = "", namespace = "", base_domain = "", cluster = "") {
    if (cluster) {
        def link = "https://$name-$namespace.$base_domain"
        slack("$token", "good", "$type Success", "`$name` `$version` :satellite: `$namespace` :earth_asia: `$cluster`", "$JOB_NAME <$RUN_DISPLAY_URL|#$BUILD_NUMBER> : <$link|$name-$namespace>")
    } else if (base_domain) {
    def link = "https://$name-$namespace.$base_domain"
        slack("$token", "good", "$type Success", "`$name` `$version` :satellite: `$namespace`", "$JOB_NAME <$RUN_DISPLAY_URL|#$BUILD_NUMBER> : <$link|$name-$namespace>")
    } else if (namespace) {
        slack("$token", "good", "$type Success", "`$name` `$version` :rocket: `$namespace`", "$JOB_NAME <$RUN_DISPLAY_URL|#$BUILD_NUMBER>")
    } else {
        slack("$token", "good", "$type Success", "`$name` `$version` :heavy_check_mark:", "$JOB_NAME <$RUN_DISPLAY_URL|#$BUILD_NUMBER>")
    }
}

def proceed(token = "", type = "", name = "", version = "", namespace = "") {
    slack("$token", "warning", "$type Proceed?", "`$name` `$version` :rocket: `$namespace`", "$JOB_NAME <$RUN_DISPLAY_URL|#$BUILD_NUMBER>")
}

def slack(token = "", color = "", title = "", message = "", footer = "") {
    try {
        sh """
            curl -sL repo.opsnow.io/valve-ctl/slack | bash -s -- --token=\'$token\' \
            --footer=\'$footer\' --footer_icon='https://jenkins.io/sites/default/files/jenkins_favicon.ico' \
            --color=\'$color\' --title=\'$title\' \'$message\'
        """
    } catch (ignored) {
    }
}
