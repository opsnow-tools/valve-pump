#!/usr/bin/groovy
package com.opsnow.valve;

def prepare(namespace = "devops") {
    this.base_domain = ""
    this.slack_token = ""
    this.helm_state = ""

    // domains
    this.jenkins = scan_domain("jenkins", namespace)
    if (this.jenkins) {
        this.base_domain = this.jenkins.substring(this.jenkins.indexOf('.') + 1)
    }

    this.chartmuseum = scan_domain("chartmuseum", namespace)
    this.registry = scan_domain("docker-registry", namespace)
    this.sonarqube = scan_domain("sonarqube", namespace)
    this.nexus = scan_domain("sonatype-nexus", namespace)

    // slack token
    scan_slack_token()
}

def scan(name = "sample", branch = "master", source_lang = "") {
    this.name = name
    this.source_lang = source_lang
    this.source_root = "."

    date = (new Date()).format('yyyyMMdd-HHmm')
    version = "v0.0.0-$date"

    // version
    // if (branch == "master") {
    //     version = "v0.1.1-$date"
    // } else {
    //     version = "v0.0.1-$date"
    // }

    this.version = version
    echo "# version: $version"

    // language
    if (!this.source_lang || this.source_lang == "java") {
        scan_langusge("pom.xml", "java")
    }
    if (!this.source_lang || this.source_lang == "nodejs") {
        scan_langusge("package.json", "nodejs")
    }

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

            echo "# source_lang: $source_lang"
            echo "# source_root: $source_root"

            // maven mirror
            if (source_lang == 'java') {
                // replace this.version
                // dir(source_root) {
                //     sh "sed -i -e \"s|(<this.version>)(.*)(</this.version>)|\1${this.version}\3|\" pom.xml | true"
                // }

                if (this.nexus) {
                    def m2_home = "/home/jenkins/.m2"

                    sh "mkdir -p $m2_home"
                    sh "cp -f /root/.m2/settings.xml $m2_home/settings.xml | true"

                    def mirror_of = "*,!nexus-public,!nexus-releases,!nexus-snapshots"
                    def mirror_url = "http://${this.nexus}/repository/maven-public/"
                    def mirror_xml = "<mirror><id>mirror</id><url>${mirror_url}</url><mirrorOf>${mirror_of}</mirrorOf></mirror>"

                    echo "# mirror_url: $mirror_url"

                    // replace maven-public
                    sh "sed -i -e \"s|<!-- ### configured mirrors ### -->|${mirror_xml}|\" $m2_home/settings.xml | true"
                }
            }
        }
    }
}

def scan_domain(target = "", namespace = "") {
    if (!target) {
        echo "scan_domain:target is null."
        throw new RuntimeException("target is null.")
    }
    if (!namespace) {
        echo "scan_domain:namespace is null."
        throw new RuntimeException("namespace is null.")
    }

    def domain = sh(script: "kubectl get ing -n $namespace -o wide | grep $target | awk '{print \$2}'", returnStdout: true).trim()

    if (domain && this.base_domain) {
        domain = "$target-$namespace.${this.base_domain}"
    }

    echo "# $target: $domain"

    return domain
}

def scan_slack_token(namespace = "devops") {
    def token = sh(script: "kubectl get secret slack-token -n $namespace -o json | jq -r .data.text | base64 -d", returnStdout: true).trim()
    if (token) {
        echo "# slack-token: $token"
        this.slack_token = token
    }
}

def env_cluster(cluster = "", namespace = "devops") {
    if (!cluster) {
        // throw new RuntimeException("cluster is null.")
        return
    }

    sh "rm -rf $home/.kube"
    sh "mkdir -p $home/.kube"

    // check cluster secret
    count = sh(script: "kubectl get secret -n $namespace | grep 'kube-config-$cluster' | wc -l", returnStdout: true).trim()
    if ("$count" == "0") {
        throw new RuntimeException("cluster is null.")
    }

    sh "kubectl get secret kube-config-$cluster -n $namespace -o json | jq -r .data.text | base64 -d > $home/.kube/config"

    // sh "kubectl cluster-info"
    sh "kubectl config current-context"

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

def apply_config(type = "", name = "", namespace = "", cluster = "", path = "") {
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

    // config yaml
    def yaml = ""
    if (path) {
        yaml = "${path}"
    } else {
        if (cluster) {
            yaml = sh(script: "find . -name ${name}.yaml | grep $type/$cluster/$namespace/${name}.yaml | head -1", returnStdout: true).trim()
        } else {
            yaml = sh(script: "find . -name ${name}.yaml | grep $type/$namespace/${name}.yaml | head -1", returnStdout: true).trim()
        }

        if (!yaml) {
            throw new RuntimeException("yaml is null.")
        }
    }

    // replace metadata.name
    sh "sed -i -e \"s|name: REPLACE-ME|name: $name-$namespace|\" $yaml"

    // apply secret
    sh "kubectl apply -n $namespace -f $yaml"

    if (!path) {
        // describe secret
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

    def chart = sh(script: "find . -name Chart.yaml | head -1", returnStdout: true).trim()
    if (chart) {
        // sh "mv charts/acme charts/$name"

        dir("charts/$name") {
            sh "sed -i -e \"s/name: .*/name: $name/\" Chart.yaml"
            sh "sed -i -e \"s/version: .*/version: $version/\" Chart.yaml"

            sh "sed -i -e \"s/tag: .*/tag: $version/g\" values.yaml"

            if (registry) {
                sh "sed -i -e \"s|repository: .*|repository: $registry/$name|\" values.yaml"
            }

            if (base_domain) {
                sh "sed -i -e \"s|basedomain: .*|basedomain: $base_domain|\" values.yaml"
            }
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

    // check push plugin
    count = sh(script: "helm plugin list | grep 'Push chart package' | wc -l", returnStdout: true).trim()
    if ("$count" == "0") {
        sh "helm plugin install https://github.com/chartmuseum/helm-push"
    }
    sh "helm plugin list"

    // check chart
    chart = sh(script: "find . -name Chart.yaml | head -1", returnStdout: true).trim()
    if (chart) {
        dir("charts/$name") {
            sh "helm lint ."

            if (chartmuseum) {
                sh "helm push . chartmuseum"
            }
        }
    }

    sh "helm repo update"
    sh "helm search $name"
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
    if (this.helm_state) {
        return
    }

    sh "helm init --upgrade"
    sh "helm version"

    if (chartmuseum) {
        sh "helm repo add chartmuseum https://$chartmuseum"
    }

    sh "helm repo list"
    sh "helm repo update"

    this.helm_state = "initialized"
}

def helm_install(name = "", version = "", namespace = "", base_domain = "", cluster = "") {
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

    // if (!base_domain) {
    //     base_domain = this.base_domain
    // }

    profile = "$namespace"
    // if (cluster) {
    //     profile = "$cluster-$namespace"
    // }

    // cluster
    env_cluster(cluster)

    // namespace
    env_namespace(namespace)

    // config (secret, configmap)
    // configmap = env_config("configmap", name, namespace)
    // secret = env_config("secret", name, namespace)

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
                     --set replicaCount=$desired \
                     --set profile=$profile
    """

    sh "helm search $name"
    sh "helm history $name-$namespace --max 5"
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

    // cluster
    env_cluster(cluster)

    helm_init()

    sh "helm search $name"
    sh "helm history $name-$namespace --max 5"

    sh "helm delete --purge $name-$namespace"
}

def draft_init() {
    sh "draft init"
    sh "draft version"

    if (registry) {
        sh "draft config set registry $registry"
    }
}

def draft_up(name = "", namespace = "", base_domain = "", cluster = "") {
    if (!name) {
        echo "draft_up:name is null."
        throw new RuntimeException("name is null.")
    }
    if (!namespace) {
        echo "draft_up:namespace is null."
        throw new RuntimeException("namespace is null.")
    }
    if (!base_domain) {
        echo "draft_up:base_domain is null."
        throw new RuntimeException("base_domain is null.")
    }

    // if (!base_domain) {
    //     base_domain = this.base_domain
    // }

    // cluster
    env_cluster(cluster)

    // namespace
    env_namespace(namespace)

    draft_init()

    sh "sed -i -e \"s/NAMESPACE/$namespace/g\" draft.toml"
    sh "sed -i -e \"s/NAME/$name-$namespace/g\" draft.toml"

    sh "draft up -e $namespace"

    sh "draft logs"
}

def npm_build() {
    def source_root = this.source_root
    dir("$source_root") {
        sh "npm run build"
    }
}

def mvn_build() {
    def source_root = this.source_root
    dir("$source_root") {
        if (this.nexus) {
            sh "mvn package -s /home/jenkins/.m2/settings.xml -DskipTests=true"
        } else {
            sh "mvn package -DskipTests=true"
        }
    }
}

def mvn_test() {
    def source_root = this.source_root
    dir("$source_root") {
        if (this.nexus) {
            sh "mvn test -s /home/jenkins/.m2/settings.xml"
        } else {
            sh "mvn test"
        }
    }
}

def mvn_deploy() {
    def source_root = this.source_root
    dir("$source_root") {
        if (this.nexus) {
            sh "mvn deploy -s /home/jenkins/.m2/settings.xml -DskipTests=true"
        } else {
            sh "mvn deploy -DskipTests=true"
        }
    }
}

def mvn_sonar() {
    def sonarqube = this.sonarqube
    if (sonarqube) {
        def source_root = this.source_root
        dir("$source_root") {
            if (this.nexus) {
                sh "mvn sonar:sonar -s /home/jenkins/.m2/settings.xml -Dsonar.host.url=http://$sonarqube -DskipTests=true"
            } else {
                sh "mvn sonar:sonar -Dsonar.host.url=$sonarqube -DskipTests=true"
            }
        }
    }
}

def failure(type = "", name = "") {
  slack("danger", "$type Failure", "`$name`", "$JOB_NAME <$RUN_DISPLAY_URL|#$BUILD_NUMBER>")
}

def success(type = "", name = "", version = "", namespace = "", base_domain = "", cluster = "") {
  if (cluster) {
    def link = "https://$name-$namespace.$base_domain"
    slack("good", "$type Success", "`$name` `$version` :satellite: `$namespace` :earth_asia: `$cluster`", "$JOB_NAME <$RUN_DISPLAY_URL|#$BUILD_NUMBER> : <$link|$name-$namespace>")
  } else if (base_domain) {
    def link = "https://$name-$namespace.$base_domain"
    slack("good", "$type Success", "`$name` `$version` :satellite: `$namespace`", "$JOB_NAME <$RUN_DISPLAY_URL|#$BUILD_NUMBER> : <$link|$name-$namespace>")
  } else if (namespace) {
    slack("good", "$type Success", "`$name` `$version` :rocket: `$namespace`", "$JOB_NAME <$RUN_DISPLAY_URL|#$BUILD_NUMBER>")
  } else {
    slack("good", "$type Success", "`$name` `$version` :heavy_check_mark:", "$JOB_NAME <$RUN_DISPLAY_URL|#$BUILD_NUMBER>")
  }
}

def proceed(type = "", name = "", version = "", namespace = "") {
  slack("warning", "$type Proceed?", "`$name` `$version` :rocket: `$namespace`", "$JOB_NAME <$RUN_DISPLAY_URL|#$BUILD_NUMBER>")
}

def slack(color = "", title = "", message = "", footer = "") {
    try {
        if (this.slack_token) {
            sh """
                curl -sL toast.sh/slack | bash -s -- \
                    --token='${this.slack_token}' \
                    --emoji=":construction_worker:" --username="valve" \
                    --color='$color' --title='$title' --footer='$footer' '$message'
            """
        }
    } catch (ignored) {
    }
}
