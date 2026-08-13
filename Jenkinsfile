// ════════════════════════════════════════════════════════════════
// Automation Pipeline — amazon-test-automation repo (K8s version)
//
// Replaces docker-compose with kubectl apply against the k3s cluster
// running on the qa-agent EC2 instance itself. Reuses ECR login and
// tag-resolution logic unchanged from the compose-based version —
// only "Start Infrastructure" and "Start Microservices" are replaced,
// plus a new port-forward stage since K8s Services aren't reachable
// on localhost the way compose's port mappings were.
// ════════════════════════════════════════════════════════════════

pipeline {
    agent { label 'qa-agent' }
    tools {
        allure 'Allure-2.40.0'
    }

    options {
        timeout(time: 90, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
        disableConcurrentBuilds()
        timestamps()
        ansiColor('xterm')
    }

    parameters {
        string(name: 'IMAGE_TAG', defaultValue: 'latest', description: 'Docker image tag to test.')
        string(name: 'TRIGGERED_BY', defaultValue: 'manual', description: 'Which pipeline triggered this run.')
        string(name: 'GIT_COMMIT', defaultValue: '', description: 'Git commit SHA from dev pipeline.')
        string(name: 'BRANCH', defaultValue: 'main', description: 'Branch that triggered the dev build.')
        booleanParam(name: 'SKIP_E2E', defaultValue: false, description: 'Skip E2E tests.')
    }

    environment {
        AWS_REGION     = "us-east-1"
        AWS_ACCOUNT_ID = "978185568053"
        REGISTRY       = "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
        PROJECT        = "amazon"
        IMAGE_TAG      = "${params.IMAGE_TAG}"
        NAMESPACE      = "amazon"
        KUBECONFIG     = "/home/ubuntu/.kube/config"
        MAVEN_OPTS     = "-Xmx256m -XX:+UseG1GC"

        BASE_URL       = "http://localhost:8090"
        DB_HOST        = "localhost"
        KAFKA_SERVERS  = "localhost:9092"
        REDIS_HOST     = "localhost"
    }

    stages {

        // ── ECR Login ──────────────────────────────────────────────
        // Unchanged from the compose version — still needed for the
        // Context stage's docker pull checks below, and reused again
        // when we build the K8s imagePullSecret.
        stage('ECR Login') {
            steps {
                withCredentials([[
                    $class: 'AmazonWebServicesCredentialsBinding',
                    credentialsId: 'aws-ecr-creds'
                ]]) {
                    sh """
                        aws ecr get-login-password --region ${AWS_REGION} | \
                          docker login --username AWS --password-stdin ${REGISTRY}
                    """
                }
                echo "✅ Authenticated to ECR: ${REGISTRY}"
            }
        }

        // ── Context: resolve per-service tags ─────────────────────
        // UNCHANGED from the compose version. Still uses `docker pull`
        // on the agent itself to check whether IMAGE_TAG exists per
        // service, falling back to :latest — same logic, same reason
        // (changed services get tested at exact commit, unchanged
        // services get last known good). The resolved TAG_* env vars
        // get substituted into the K8s manifests further down instead
        // of into docker-compose's environment.
        stage('Context') {
            steps {
                echo """
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🧪 Automation Pipeline Starting (K8s)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Image Tag:    ${params.IMAGE_TAG}
Triggered By: ${params.TRIGGERED_BY}
Skip E2E:     ${params.SKIP_E2E}
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"""
                script {
                    def services = [
                        'user-service', 'product-service', 'order-service',
                        'payment-service', 'notification-service', 'api-gateway'
                    ]
                    def resolvedTags = [:]
                    def pullTasks = [:]
                    services.each { svc ->
                        def s = svc
                        pullTasks[s] = {
                            def specificImage = "${REGISTRY}/amazon-${s}:${IMAGE_TAG}"
                            def rc = sh(script: "docker pull ${specificImage} > /dev/null 2>&1", returnStatus: true)
                            if (rc == 0) {
                                resolvedTags[s] = IMAGE_TAG
                                echo "✅ ${s}: using tag ${IMAGE_TAG}"
                            } else {
                                def latestRc = sh(script: "docker pull ${REGISTRY}/amazon-${s}:latest > /dev/null 2>&1", returnStatus: true)
                                if (latestRc == 0) {
                                    resolvedTags[s] = 'latest'
                                    echo "⏩ ${s}: tag ${IMAGE_TAG} not found — using :latest"
                                } else {
                                    error("❌ ${s}: neither :${IMAGE_TAG} nor :latest found in ECR.")
                                }
                            }
                        }
                    }
                    parallel pullTasks

                    env.TAG_USER_SERVICE         = resolvedTags['user-service']
                    env.TAG_PRODUCT_SERVICE      = resolvedTags['product-service']
                    env.TAG_ORDER_SERVICE        = resolvedTags['order-service']
                    env.TAG_PAYMENT_SERVICE      = resolvedTags['payment-service']
                    env.TAG_NOTIFICATION_SERVICE = resolvedTags['notification-service']
                    env.TAG_API_GATEWAY          = resolvedTags['api-gateway']
                }
            }
        }

        stage('Checkout Infrastructure') {
            steps {
                script {
                    echo "📦 Checking out infrastructure configuration..."
                    dir('../amazon-microservices') {
                        checkout([
                            $class: 'GitSCM',
                            branches: [[name: '*/master']],
                            userRemoteConfigs: [[
                                url: 'https://github.com/rab-tab/amazon-microservices',
                                credentialsId: 'github-token'
                            ]]
                        ])
                    }
                    echo "✅ Infrastructure repo checked out (includes k8s/ manifests)"
                }
            }
        }

        // ── Kubernetes Setup ───────────────────────────────────────
        // New stage, no compose equivalent. Ensures the namespace
        // exists and refreshes the ECR pull secret — ECR tokens expire
        // ~12h, so this can't be a one-time manual step; it has to
        // run fresh every pipeline execution.
        stage('Kubernetes Setup') {
            steps {
                withCredentials([[
                    $class: 'AmazonWebServicesCredentialsBinding',
                    credentialsId: 'aws-ecr-creds'
                ]]) {
                    sh """
                        kubectl create namespace ${NAMESPACE} --dry-run=client -o yaml | kubectl apply -f -

                        kubectl delete secret ecr-registry-secret -n ${NAMESPACE} --ignore-not-found

                        kubectl create secret docker-registry ecr-registry-secret \
                          --docker-server=${REGISTRY} \
                          --docker-username=AWS \
                          --docker-password="\$(aws ecr get-login-password --region ${AWS_REGION})" \
                          -n ${NAMESPACE}
                    """
                }
                echo "✅ Namespace ready, ECR pull secret refreshed"
            }
        }

        // ── Deploy Infrastructure to K8s ───────────────────────────
        // Replaces "Start Infrastructure" (docker-compose up postgres
        // ── Deploy to Kubernetes (Kustomize) ───────────────────────
        // Replaces both "Start Infrastructure" and "Start Microservices"
        // from the compose version. The repo's k8s/ folder already had
        // a kustomization.yaml (Kustomize) tying together 8 resource
        // files across subfolders — we use kustomize's own `edit set
        // image` to inject each service's resolved tag (from Context
        // stage) at the correct full ECR image name, then a single
        // `kubectl apply -k .` applies everything in dependency-safe
        // order in one shot, replacing the old sed-placeholder hack.
        //
        // NOTE: `kubectl kustomize edit ...` is NOT valid — kubectl's
        // built-in kustomize support only BUILDS (renders) a
        // kustomization; it has no "edit" subcommand. "kustomize edit
        // set image" only exists in the separate, standalone kustomize
        // CLI binary, which isn't installed on this agent. Instead we
        // append an images: block directly via a heredoc — kubectl's
        // built-in kustomize DOES correctly honor this block once it's
        // present, we just can't use "edit" to write it. Safe to append
        // fresh every run since checkout starts clean each time (no
        // risk of duplicate blocks accumulating across runs).
        stage('Deploy to Kubernetes') {
            steps {
                dir('../amazon-microservices/k8s') {
                    sh """
                        echo "Cleaning up any leftover resources from a previous run..."
                        kubectl delete deployment,statefulset --all -n ${NAMESPACE} --ignore-not-found
                        kubectl delete pod --all -n ${NAMESPACE} --ignore-not-found --grace-period=0 --force 2>/dev/null || true

                        echo "Setting resolved image tags via kustomize..."
                        echo "" >> kustomization.yaml
                        cat >> kustomization.yaml << EOF
images:
  - name: ${REGISTRY}/amazon-user-service
    newTag: "\${TAG_USER_SERVICE}"
  - name: ${REGISTRY}/amazon-product-service
    newTag: "\${TAG_PRODUCT_SERVICE}"
  - name: ${REGISTRY}/amazon-order-service
    newTag: "\${TAG_ORDER_SERVICE}"
  - name: ${REGISTRY}/amazon-payment-service
    newTag: "\${TAG_PAYMENT_SERVICE}"
  - name: ${REGISTRY}/amazon-notification-service
    newTag: "\${TAG_NOTIFICATION_SERVICE}"
  - name: ${REGISTRY}/amazon-api-gateway
    newTag: "\${TAG_API_GATEWAY}"
EOF

                        echo "Applying full manifest set via kustomize..."
                        kubectl apply -k .

                        echo "⏳ Waiting for infrastructure pods..."
                        kubectl wait --for=condition=ready pod -l app=postgres   -n ${NAMESPACE} --timeout=120s
                        kubectl wait --for=condition=ready pod -l app=redis      -n ${NAMESPACE} --timeout=60s
                        kubectl wait --for=condition=ready pod -l app=zookeeper  -n ${NAMESPACE} --timeout=60s
                        kubectl wait --for=condition=ready pod -l app=kafka      -n ${NAMESPACE} --timeout=180s

                        echo "⏳ Waiting for microservices..."
                        kubectl wait --for=condition=ready pod -l app=user-service         -n ${NAMESPACE} --timeout=300s
                        kubectl wait --for=condition=ready pod -l app=product-service      -n ${NAMESPACE} --timeout=300s
                        kubectl wait --for=condition=ready pod -l app=order-service        -n ${NAMESPACE} --timeout=300s
                        kubectl wait --for=condition=ready pod -l app=payment-service      -n ${NAMESPACE} --timeout=300s
                        kubectl wait --for=condition=ready pod -l app=notification-service -n ${NAMESPACE} --timeout=120s
                        kubectl wait --for=condition=ready pod -l app=api-gateway          -n ${NAMESPACE} --timeout=300s
                    """
                }
                echo "✅ Infrastructure and microservices are healthy"
                sh "kubectl get pods -n ${NAMESPACE} -o wide"
            }
        }

        // ── Port Forward Services ──────────────────────────────────
        // New stage, no compose equivalent needed there (compose ports
        // were already on localhost). K8s ClusterIP Services aren't
        // reachable from the agent directly, so we tunnel each one the
        // test suite needs onto localhost, matching exactly the ports
        // BASE_URL/DB_HOST/KAFKA_SERVERS/REDIS_HOST above expect.
        // Each port-forward runs as a background process; PIDs are
        // saved to a file so post{always{}} can clean them up reliably
        // even if a later stage fails.
        stage('Port Forward Services') {
            steps {
                sh """
                    rm -f /tmp/port-forward-pids.txt

                    kubectl port-forward -n ${NAMESPACE} svc/postgres-service       5432:5432 > /tmp/pf-postgres.log 2>&1 &
                    echo \$! >> /tmp/port-forward-pids.txt
                    kubectl port-forward -n ${NAMESPACE} svc/redis-service         6379:6379 > /tmp/pf-redis.log 2>&1 &
                    echo \$! >> /tmp/port-forward-pids.txt
                    kubectl port-forward -n ${NAMESPACE} svc/kafka-service         9092:9092 > /tmp/pf-kafka.log 2>&1 &
                    echo \$! >> /tmp/port-forward-pids.txt
                    kubectl port-forward -n ${NAMESPACE} svc/user-service         8081:8081 > /tmp/pf-user.log 2>&1 &
                    echo \$! >> /tmp/port-forward-pids.txt
                    kubectl port-forward -n ${NAMESPACE} svc/product-service      8082:8082 > /tmp/pf-product.log 2>&1 &
                    echo \$! >> /tmp/port-forward-pids.txt
                    kubectl port-forward -n ${NAMESPACE} svc/order-service        8083:8083 > /tmp/pf-order.log 2>&1 &
                    echo \$! >> /tmp/port-forward-pids.txt
                    kubectl port-forward -n ${NAMESPACE} svc/payment-service      8084:8084 > /tmp/pf-payment.log 2>&1 &
                    echo \$! >> /tmp/port-forward-pids.txt
                    kubectl port-forward -n ${NAMESPACE} svc/api-gateway         8090:8080 > /tmp/pf-gateway.log 2>&1 &
                    echo \$! >> /tmp/port-forward-pids.txt

                    sleep 5

                    echo "=== Port-forward status ==="
                    for f in /tmp/pf-*.log; do echo "--- \$f ---"; cat "\$f"; done

                    echo "=== Verifying tunnels ==="
                    curl -s --max-time 3 http://localhost:8090/actuator/health || echo "⚠️ api-gateway tunnel not responding yet"
                """
                echo "✅ Port-forwards established"
            }
        }

        stage('Compile Tests') {
            steps {
                checkout scm
                sh '''
                    mvn clean compile test-compile --no-transfer-progress -q
                    echo "✅ Test code compiled successfully"
                '''
            }
        }

        stage('Run Tests') {
            parallel {
                stage('Regression Tests') {
                    when { not { expression { params.SKIP_E2E } } }
                    steps { runTestSuite('regression.xml', 'REgression suite') }
                    post { always { collectTestResults() } }
                }
            }
        }

        // SonarQube/Quality Gate stages removed — no SonarQube server
        // configured in this Jenkins instance. Not required for the
        // pipeline's core job (verifying the app works via tests);
        // re-add if a SonarQube server is set up later.
    }

    post {
        always {
            script {
                // Kill port-forwards FIRST — leaving them running would
                // leak processes and hold ports across pipeline runs.
                sh '''
                    if [ -f /tmp/port-forward-pids.txt ]; then
                        while read pid; do
                            kill $pid 2>/dev/null || true
                        done < /tmp/port-forward-pids.txt
                        rm -f /tmp/port-forward-pids.txt
                    fi
                '''

                // Dump pod logs before tearing down — kubectl logs
                // instead of docker logs, otherwise same intent as
                // compose's log-dump-before-cleanup pattern.
                echo "=== Pod logs ==="
                ['user-service','product-service','order-service',
                 'payment-service','notification-service','api-gateway'].each { svc ->
                    echo "--- ${svc} ---"
                    sh "kubectl logs -n ${NAMESPACE} -l app=${svc} --tail=50 2>&1 || echo '(${svc} not running)'"
                }

                sh """
                    kubectl delete deployment,statefulset --all -n ${NAMESPACE} --ignore-not-found
                    kubectl delete pod --all -n ${NAMESPACE} --ignore-not-found --grace-period=0 --force 2>/dev/null || true
                    echo "✅ K8s resources cleaned up"
                """

                junit allowEmptyResults: true,
                      testResults: '**/target/surefire-reports/TEST-*.xml'

                allure([
                    includeProperties: true,
                    reportBuildPolicy: 'ALWAYS',
                    results: [[path: 'target/allure-results']]
                ])

                archiveArtifacts artifacts: 'target/diagnostics/**, target/extent-reports/**, target/allure-results/**',
                                  allowEmptyArchive: true,
                                  fingerprint: false
            }
        }

        success {
            echo """
╔══════════════════════════════════════════════════════╗
║  ✅ Automation Pipeline PASSED (K8s)                  ║
║  Image Tag:  ${params.IMAGE_TAG.padRight(40)}║
╚══════════════════════════════════════════════════════╝"""
        }

        unstable {
            echo "⚠️  Automation Pipeline UNSTABLE — check Allure report"
        }

        cleanup {
            cleanWs()
        }
    }
}

// ════════════════════════════════════════════════════════════════
// Helper functions — unchanged from the compose version
// ════════════════════════════════════════════════════════════════

def runTestSuite(String suite, String displayName) {
    echo "\n━━━ Running: ${displayName} ━━━"
    sh """
        mvn test \
          -Dsurefire.suiteXmlFiles=src/test/resources/${suite} \
          -Dbase.url=${BASE_URL} \
          -Duser.service.url=http://localhost:8081 \
          -Dproduct.service.url=http://localhost:8082 \
          -Dorder.service.url=http://localhost:8083 \
          -Dkafka.bootstrap.servers=${KAFKA_SERVERS} \
          -Ddb.host=${DB_HOST} \
          -Ddb.port=5432 \
          -Ddb.username=amazon \
          -Ddb.password=amazon123 \
          -Dredis.host=${REDIS_HOST} \
          -Dredis.password=redis123 \
          --no-transfer-progress \
          -Dmaven.test.failure.ignore=true
    """
}

def collectTestResults() {
    junit allowEmptyResults: true,
          testResults: 'target/surefire-reports/TEST-*.xml'
}