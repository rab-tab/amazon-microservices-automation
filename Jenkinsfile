// ════════════════════════════════════════════════════════════════
// Automation Pipeline — amazon-test-automation repo (K8s + sharded)
//
// Replaces docker-compose with kubectl apply against the k3s cluster
// running on the qa-agent EC2 instance itself. ECR login and
// tag-resolution logic unchanged from the compose-based version.
//
// Testing moved from sequential (single mvn test on the agent, with
// port-forwarded localhost access) to sharded (a K8s Indexed Job
// running 3 pods in parallel, in-cluster, via docker.properties'
// real Service DNS names — no port-forwarding needed for this path).
// "Compile Tests", "Run Tests", and "Port Forward Services" are gone,
// replaced by "Build & Push Test Image", "Run Sharded Tests", and
// "Aggregate Test Results".
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
        S3_BUCKET      = "amazon-microservices-build-artifacts-978185568053"
    }

    stages {

        // ── ECR Login ──────────────────────────────────────────────
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
        stage('Context') {
            steps {
                echo """
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🧪 Automation Pipeline Starting (K8s, sharded)
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

        // ── Deploy to Kubernetes (Kustomize) ───────────────────────
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

        // ── Build & Push Test Image ────────────────────────────────
        // Builds amazon-test-automation fresh from this repo's current
        // source, tagged with this build's own number (not just
        // :latest) so it's always clear exactly which test code ran
        // for any given pipeline run.
        stage('Build & Push Test Image') {
            steps {
                withCredentials([[
                    $class: 'AmazonWebServicesCredentialsBinding',
                    credentialsId: 'aws-ecr-creds'
                ]]) {
                    sh """
                        aws ecr get-login-password --region ${AWS_REGION} | \
                          docker login --username AWS --password-stdin ${REGISTRY}

                        docker build -t ${REGISTRY}/amazon-test-automation:${BUILD_NUMBER} \
                                     -t ${REGISTRY}/amazon-test-automation:latest .

                        docker push ${REGISTRY}/amazon-test-automation:${BUILD_NUMBER}
                        docker push ${REGISTRY}/amazon-test-automation:latest
                    """
                }
                echo "✅ Test image pushed: amazon-test-automation:${BUILD_NUMBER}"
            }
        }

        // ── Run Sharded Tests ───────────────────────────────────────
        // Creates a fresh AWS credentials Secret (pods need this to
        // upload results to S3), substitutes this build's number into
        // the Job manifest, applies it, then polls until all 3 shards
        // finish. kubectl wait --for=condition=complete doesn't
        // cleanly handle the failure case (only succeeds on Complete,
        // so a failed Job would just time out) — polling .status
        // directly, same pattern as the CodeBuild polling used
        // elsewhere in the dev pipeline.
        stage('Run Sharded Tests') {
            when { not { expression { params.SKIP_E2E } } }
            steps {
                dir('../amazon-microservices/k8s') {
                    withCredentials([[
                        $class: 'AmazonWebServicesCredentialsBinding',
                        credentialsId: 'aws-ecr-creds'
                    ]]) {
                        sh """
                            kubectl delete secret aws-s3-creds -n ${NAMESPACE} --ignore-not-found
                            kubectl create secret generic aws-s3-creds \
                              --from-literal=access-key-id=\$AWS_ACCESS_KEY_ID \
                              --from-literal=secret-access-key=\$AWS_SECRET_ACCESS_KEY \
                              -n ${NAMESPACE}

                            sed "s/__BUILD_ID__/${BUILD_NUMBER}/g" sharded-test-job.yaml > sharded-test-job-resolved.yaml

                            kubectl delete job sharded-test-run -n ${NAMESPACE} --ignore-not-found
                            kubectl apply -f sharded-test-job-resolved.yaml
                        """
                    }

                    script {
                        def elapsed = 0
                        def timeoutSecs = 600
                        def status = 'RUNNING'

                        while (elapsed < timeoutSecs) {
                            sleep(15)
                            elapsed += 15

                            def succeeded = sh(
                                script: "kubectl get job sharded-test-run -n ${NAMESPACE} -o jsonpath='{.status.succeeded}'",
                                returnStdout: true
                            ).trim()
                            def failed = sh(
                                script: "kubectl get job sharded-test-run -n ${NAMESPACE} -o jsonpath='{.status.failed}'",
                                returnStdout: true
                            ).trim()

                            echo "  Sharded Job: succeeded=${succeeded ?: 0} failed=${failed ?: 0} (${elapsed}s)"

                            if (succeeded == '3') {
                                status = 'SUCCEEDED'
                                break
                            }
                            if (failed && failed.toInteger() > 0) {
                                status = 'FAILED'
                                break
                            }
                        }

                        if (status == 'RUNNING') {
                            error("❌ Sharded test Job did not complete within ${timeoutSecs}s")
                        }

                        echo "=== Shard pod logs ==="
                        sh "kubectl logs -n ${NAMESPACE} -l job-name=sharded-test-run --prefix=true --tail=100 || true"

                        if (status == 'FAILED') {
                            echo "⚠️  One or more shards failed — marking build UNSTABLE"
                            currentBuild.result = 'UNSTABLE'
                        } else {
                            echo "✅ All 3 shards succeeded"
                        }
                    }
                }
            }
        }

        // ── Aggregate Test Results ──────────────────────────────────
        // Downloads each shard's results into ITS OWN subfolder rather
        // than flattening them together — Surefire writes one XML per
        // test CLASS, and since sharding splits by method, two shards
        // can easily own different methods of the same class, which
        // would produce identically-named files if flattened.
        stage('Aggregate Test Results') {
            when { not { expression { params.SKIP_E2E } } }
            steps {
                withCredentials([[
                    $class: 'AmazonWebServicesCredentialsBinding',
                    credentialsId: 'aws-ecr-creds'
                ]]) {
                    sh """
                        mkdir -p target/aggregated-results
                        aws s3 sync s3://${S3_BUCKET}/test-results/${BUILD_NUMBER}/ target/aggregated-results/ \
                          --only-show-errors
                    """
                }
                echo "✅ Shard results downloaded to target/aggregated-results/"
                sh "find target/aggregated-results -name '*.xml' | wc -l"
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
                echo "=== Pod logs ==="
                ['user-service','product-service','order-service',
                 'payment-service','notification-service','api-gateway'].each { svc ->
                    echo "--- ${svc} ---"
                    sh "kubectl logs -n ${NAMESPACE} -l app=${svc} --tail=50 2>&1 || echo '(${svc} not running)'"
                }

                sh """
                    kubectl delete job sharded-test-run -n ${NAMESPACE} --ignore-not-found
                    kubectl delete secret aws-s3-creds -n ${NAMESPACE} --ignore-not-found
                    kubectl delete deployment,statefulset --all -n ${NAMESPACE} --ignore-not-found
                    kubectl delete pod --all -n ${NAMESPACE} --ignore-not-found --grace-period=0 --force 2>/dev/null || true
                    echo "✅ K8s resources cleaned up"
                """

                junit allowEmptyResults: true,
                      testResults: 'target/aggregated-results/**/surefire-reports/TEST-*.xml'

                allure([
                    includeProperties: true,
                    reportBuildPolicy: 'ALWAYS',
                    results: [
                        [path: 'target/aggregated-results/shard-0/allure-results'],
                        [path: 'target/aggregated-results/shard-1/allure-results'],
                        [path: 'target/aggregated-results/shard-2/allure-results']
                    ]
                ])

                archiveArtifacts artifacts: 'target/aggregated-results/**',
                                  allowEmptyArchive: true,
                                  fingerprint: false
            }
        }

        success {
            echo """
╔══════════════════════════════════════════════════════╗
║  ✅ Automation Pipeline PASSED (K8s, sharded)         ║
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