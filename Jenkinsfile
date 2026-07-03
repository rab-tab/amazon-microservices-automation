// ════════════════════════════════════════════════════════════════
// Automation Pipeline — amazon-test-automation repo
//
// Lives at: amazon-test-automation/Jenkinsfile
//
// This pipeline is triggered BY the dev pipeline (or manually).
// It receives IMAGE_TAG as a parameter so it tests the EXACT
// version that was just built — not whatever 'latest' happens to be.
//
// Flow:
//   Receive IMAGE_TAG → Pull images → Start infra (Kafka/Redis/PG)
//   → Wait for health → Start microservices → Wait for health
//   → Run tests in stages → Generate Allure report → Stop everything
//
// Key design decisions:
//   - Services start fresh every run (clean state = reliable tests)
//   - Infrastructure starts before services (dependency order)
//   - Tests run sequentially (RAM constraint + test isolation)
//   - Services always stop in post {} (even if tests fail)
//   - Allure report always generated (even if some tests fail)
// ════════════════════════════════════════════════════════════════

pipeline {
    agent any
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

    // ── Parameters — passed from dev pipeline ────────────────────
    // These become available as params.IMAGE_TAG etc.
    // When triggered manually, you fill these in the UI.
    parameters {
        string(
            name: 'IMAGE_TAG',
            defaultValue: 'latest',
            description: 'Docker image tag to test. Passed from dev pipeline (git SHA). Use "latest" for manual runs.'
        )
        string(
            name: 'TRIGGERED_BY',
            defaultValue: 'manual',
            description: 'Which pipeline triggered this run (for audit trail)'
        )
        string(
            name: 'GIT_COMMIT',
            defaultValue: '',
            description: 'Git commit SHA from dev pipeline (for reporting)'
        )
        string(
            name: 'BRANCH',
            defaultValue: 'main',
            description: 'Branch that triggered the dev build'
        )
        booleanParam(
            name: 'SKIP_E2E',
            defaultValue: false,
            description: 'Skip E2E tests (run unit-level integration tests only)'
        )
    }

    environment {
        REGISTRY      = "rabtab"
        PROJECT       = "amazon"  // images = rabtab/amazon-<service>:<tag>
        IMAGE_TAG     = "${params.IMAGE_TAG}"
        COMPOSE_FILE  = "../amazon-microservices/docker/ci/docker-compose.local.yml"
        MAVEN_OPTS    = "-Xmx256m -XX:+UseG1GC"

        // Test connection properties — passed to Maven as -D flags
        BASE_URL      = "http://localhost:8090"
        DB_HOST       = "localhost"
        KAFKA_SERVERS = "localhost:9092"
        REDIS_HOST    = "localhost"
    }

    stages {

        // ── Stage 1: Print Context ────────────────────────────────
        // Always know exactly what you're testing and why.
        // This information ends up in the build log — invaluable for debugging.
        stage('Context') {
            steps {
                echo """
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
🧪 Automation Pipeline Starting
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Image Tag:    ${params.IMAGE_TAG}
Git Commit:   ${params.GIT_COMMIT ?: 'not provided'}
Branch:       ${params.BRANCH}
Triggered By: ${params.TRIGGERED_BY}
Skip E2E:     ${params.SKIP_E2E}
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"""

                // ── Resolve per-service image tags ───────────────────
                // For each service, try to pull IMAGE_TAG first.
                // If it doesn't exist on Docker Hub (service wasn't changed
                // in this commit), fall back to :latest.
                // This means QA always runs with the best available image:
                //   - Changed services  → tested at exact commit SHA
                //   - Unchanged services → tested at last known good (latest)
                script {
                    def services = [
                        'user-service', 'product-service', 'order-service',
                        'payment-service', 'notification-service', 'api-gateway'
                    ]

                    def resolvedTags = [:]

                    // WHY parallel pulls? Each docker pull is independent network I/O.
                    // Sequential pulls waste 2-15s per image waiting for one to finish
                    // before starting the next. Parallel cuts this to max(single pull time).
                    def pullTasks = [:]
                    services.each { svc ->
                        def s = svc  // capture for closure
                        pullTasks[s] = {
                            def specificImage = "${REGISTRY}/amazon-${s}:${IMAGE_TAG}"
                            def rc = sh(
                                script: "docker pull ${specificImage} > /dev/null 2>&1",
                                returnStatus: true
                            )
                            if (rc == 0) {
                                resolvedTags[s] = IMAGE_TAG
                                echo "✅ ${s}: using tag ${IMAGE_TAG} (just built)"
                            } else {
                                def latestRc = sh(
                                    script: "docker pull ${REGISTRY}/amazon-${s}:latest > /dev/null 2>&1",
                                    returnStatus: true
                                )
                                if (latestRc == 0) {
                                    resolvedTags[s] = 'latest'
                                    echo "⏩ ${s}: tag ${IMAGE_TAG} not found — using :latest"
                                } else {
                                    error("❌ ${s}: neither :${IMAGE_TAG} nor :latest found on Docker Hub. Run a full build first.")
                                }
                            }
                        }
                    }
                    parallel pullTasks

                    // Export per-service tags as env vars for docker-compose
                    env.TAG_USER_SERVICE         = resolvedTags['user-service']
                    env.TAG_PRODUCT_SERVICE      = resolvedTags['product-service']
                    env.TAG_ORDER_SERVICE        = resolvedTags['order-service']
                    env.TAG_PAYMENT_SERVICE      = resolvedTags['payment-service']
                    env.TAG_NOTIFICATION_SERVICE = resolvedTags['notification-service']
                    env.TAG_API_GATEWAY          = resolvedTags['api-gateway']

                    echo """
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📦 Resolved Image Tags
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
user-service:         ${env.TAG_USER_SERVICE}
product-service:      ${env.TAG_PRODUCT_SERVICE}
order-service:        ${env.TAG_ORDER_SERVICE}
payment-service:      ${env.TAG_PAYMENT_SERVICE}
notification-service: ${env.TAG_NOTIFICATION_SERVICE}
api-gateway:          ${env.TAG_API_GATEWAY}
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"""
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
                    echo "✅ Infrastructure repo checked out"
                }
            }
        }

        // ── Stage 2: Start Infrastructure ─────────────────────────
        // Start Kafka, Redis, PostgreSQL FIRST.
        // Services depend on these — starting them together causes
        // race conditions and connection refused errors.
        //
        // ⚠️  COMMON ISSUE YOU'LL HIT:
        // Kafka not ready → service starts → can't connect → service crashes
        // Fix: the waitForKafka() function below polls until ready
        stage('Start Infrastructure') {
            steps {
                script {
                    // Tear down any leftover state from previous run
                    // WHY? Leftover containers can have stale data that
                    // causes tests to fail for the wrong reasons
                    sh """
                        echo "Cleaning up any leftover containers..."
                        docker-compose -f ${COMPOSE_FILE} down -v --remove-orphans 2>/dev/null || true
                        docker container prune -f --filter "label=project=amazon-local"
                    """

                    sh 'echo "Available memory before starting:"; free -h 2>/dev/null || vm_stat | head -5'

                    // Start only infrastructure, not microservices yet
                    sh """
                        export TAG_USER_SERVICE=${env.TAG_USER_SERVICE ?: 'latest'}
                        export TAG_PRODUCT_SERVICE=${env.TAG_PRODUCT_SERVICE ?: 'latest'}
                        export TAG_ORDER_SERVICE=${env.TAG_ORDER_SERVICE ?: 'latest'}
                        export TAG_PAYMENT_SERVICE=${env.TAG_PAYMENT_SERVICE ?: 'latest'}
                        export TAG_NOTIFICATION_SERVICE=${env.TAG_NOTIFICATION_SERVICE ?: 'latest'}
                        export TAG_API_GATEWAY=${env.TAG_API_GATEWAY ?: 'latest'}
                        docker-compose -f ${COMPOSE_FILE} up -d \
                            postgres redis zookeeper kafka zipkin db-init

                    """
                      sh '''
                          echo "==== Kafka configured healthcheck ===="
                          docker inspect test-kafka --format '{{json .Config.Healthcheck}}'
                         '''

                    echo "Infrastructure containers started. Waiting for health checks..."
                }

                // ── Wait for PostgreSQL ──────────────────────────
                // Each waitFor* call polls until service is ready OR times out
                script {
                    waitForContainer(
                        container: 'test-postgres',
                        command: 'pg_isready -U amazon',
                        timeoutSecs: 60,
                        description: 'PostgreSQL'
                    )
                }

                // ── Wait for Kafka ───────────────────────────────
                // Kafka is the slowest to start (needs Zookeeper first)
                // 90 seconds is realistic — Kafka JVM startup + topic init
                script {
                    waitForKafka(timeoutSecs: 180)
                }

                // ── Wait for Redis ───────────────────────────────
                script {
                    waitForContainer(
                        container: 'test-redis',
                        command: 'redis-cli -a redis123 ping',
                        timeoutSecs: 30,
                        description: 'Redis'
                    )
                }

                echo "✅ All infrastructure is healthy!"
            }
        }

        // ── Stage 3: Start Microservices ──────────────────────────
        // Only start services AFTER infrastructure is confirmed healthy.
        // Starting everything together is the #1 cause of flaky pipelines.
        stage('Start Microservices') {
            steps {
                script {
                    sh """
                        export TAG_USER_SERVICE=${env.TAG_USER_SERVICE ?: 'latest'}
                        export TAG_PRODUCT_SERVICE=${env.TAG_PRODUCT_SERVICE ?: 'latest'}
                        export TAG_ORDER_SERVICE=${env.TAG_ORDER_SERVICE ?: 'latest'}
                        export TAG_PAYMENT_SERVICE=${env.TAG_PAYMENT_SERVICE ?: 'latest'}
                        export TAG_NOTIFICATION_SERVICE=${env.TAG_NOTIFICATION_SERVICE ?: 'latest'}
                        export TAG_API_GATEWAY=${env.TAG_API_GATEWAY ?: 'latest'}
                        docker-compose -f ${COMPOSE_FILE} up -d \
                            user-service product-service order-service \
                            payment-service notification-service api-gateway
                    """

                    // WHY PARALLEL? Services all start simultaneously, so waiting
                    // sequentially wastes time — user-service taking 430s blocked everything.
                    // With parallel waits, all 5 run concurrently and we finish as soon
                    // as the LAST one is ready, not sum(all startup times).
                    echo "⏳ Waiting for all microservices in parallel..."
                    def parallelChecks = [:]
                    def serviceList = [
                        [port: 8081, name: 'User Service'],
                        [port: 8082, name: 'Product Service'],
                        [port: 8083, name: 'Order Service'],
                        [port: 8084, name: 'Payment Service'],
                        [port: 8090, name: 'API Gateway'],  // compose maps 8090:8080
                    ]
                    sh '''
                    echo "===== MEMORY ====="
                    docker stats --no-stream

                    echo
                    echo "===== CONTAINERS ====="
                    docker ps -a
                    '''
                    serviceList.each { svc ->
                        def s = svc  // capture for closure
                        parallelChecks[s.name] = {
                        sh "docker ps -a"
                            waitForHttp(
                                url: "http://localhost:${s.port}/actuator/health",
                                timeoutSecs: 400,
                                description: s.name
                            )
                        }
                    }
                    try {
                    parallel parallelChecks
                    } catch(Exception e) {
                        sh 'docker ps -a'
                        sh 'docker logs test-user-service --tail 200 || true'
                        sh 'docker logs test-product-service --tail 200 || true'
                        sh 'docker logs test-order-service --tail 200 || true'
                        sh 'docker logs test-payment-service --tail 200 || true'
                        sh 'docker logs test-api-gateway --tail 200 || true'
                        throw e
                    }

                    echo "✅ All microservices are healthy!"

                    // Show memory usage — this is educational
                    sh 'docker stats --no-stream --format "table {{.Name}}\\t{{.MemUsage}}" | head -15'
                }
            }
        }

        // ── Stage 4: Compile Test Code ────────────────────────────
        // Compile BEFORE running tests — fail fast on compile errors
        // rather than partway through a 20-minute test suite
        stage('Compile Tests') {
            steps {
                checkout scm   // Check out the test automation repo
                sh '''
                   # cd test-automation 2>/dev/null || true
                    mvn clean compile test-compile \
                        --no-transfer-progress \
                        -q
                    echo "✅ Test code compiled successfully"
                '''
            }
        }

        // ── Stage 5-10: Test Suites ───────────────────────────────
        // Each suite runs as a separate stage so:
        //   - You can see exactly which suite failed in the UI
        //   - Earlier suites don't block later ones if they partially fail
        //   - Build times per suite are visible in stage view
        //
        // continue-on-error equivalent: -Dmaven.test.failure.ignore=true
        // This means: run ALL tests even if some fail, collect all results,
        // then Jenkins marks build UNSTABLE (yellow) not FAILED (red)
        stage('Run Tests') {
        parallel {
            /*stage('Auth & User Tests') {
                steps { runTestSuite('testng-api-gateway.xml', 'Auth and User Tests') }
                post { always { collectTestResults() } }
            }

            stage('Product & Order Tests') {
                steps { runTestSuite('testng-order.xml', 'Product and Order Tests') }
                post { always { collectTestResults() } }
            }

            stage('DB Validation Tests') {
                steps { runTestSuite('testng-db.xml', 'Database Validation Tests') }
                post { always { collectTestResults() } }
            }

            stage('E2E Tests') {
                when {
                 not { expression { params.SKIP_E2E } }
                }
                steps { runTestSuite('testng-e2e.xml', 'End-to-End Tests') }
                post { always { collectTestResults() } }
            }*/
            stage('Kafka Event Tests') {
                            steps { runTestSuite('testng-kafka.xml', 'Kafka Event Tests') }
                            post { always { collectTestResults() } }
                        }
            }
        }

        // ── Stage 11: Allure Report ───────────────────────────────
        // Always run this — even if tests failed.
        // A failing test is only useful if you can see WHY it failed.
        stage('Generate Report') {
            steps {
                allure([
                    includeProperties: true,
                    reportBuildPolicy: 'ALWAYS',
                    results: [[path: 'target/allure-results']]
                ])
                echo "📊 Allure report: http://localhost:8090/job/automation-tests/${BUILD_NUMBER}/allure"
            }
        }
    }

    // ── Post actions ─────────────────────────────────────────────
    // post {} runs AFTER all stages, in ALL cases (success/failure/aborted)
    post {

        // always {} runs no matter what — dump logs FIRST, then clean up
        always {
            script {
                // Dump service logs before tearing down — essential for debugging failures
                echo "=== Service logs ==="
                ['test-user-service','test-product-service','test-order-service',
                 'test-payment-service','test-notification-service','test-api-gateway'].each { container ->
                    echo "--- ${container} ---"
                    sh "docker logs ${container} --tail 50 2>&1 || echo '(${container} not running)'"
                }

                // Now tear down — always, to avoid port conflicts on next build
                sh """
                    export TAG_USER_SERVICE=${env.TAG_USER_SERVICE ?: 'latest'}
                    export TAG_PRODUCT_SERVICE=${env.TAG_PRODUCT_SERVICE ?: 'latest'}
                    export TAG_ORDER_SERVICE=${env.TAG_ORDER_SERVICE ?: 'latest'}
                    export TAG_PAYMENT_SERVICE=${env.TAG_PAYMENT_SERVICE ?: 'latest'}
                    export TAG_NOTIFICATION_SERVICE=${env.TAG_NOTIFICATION_SERVICE ?: 'latest'}
                    export TAG_API_GATEWAY=${env.TAG_API_GATEWAY ?: 'latest'}
                    docker-compose -f ${COMPOSE_FILE} down -v --remove-orphans 2>/dev/null || true
                    docker rm -f test-kafka || true
                    //docker-compose -f docker-compose.local.yml up -d --force-recreate kafka
                    docker container prune -f --filter "label=project=amazon-local" 2>/dev/null || true
                    echo "✅ Containers stopped and cleaned up"
                """

                // Collect final JUnit results for Jenkins trend charts
                junit allowEmptyResults: true,
                      testResults: '**/target/surefire-reports/TEST-*.xml'
            }
        }

        success {
            echo """
╔══════════════════════════════════════════════════════╗
║  ✅ Automation Pipeline PASSED                        ║
║  Image Tag:  ${params.IMAGE_TAG.padRight(40)}║
║  Triggered:  ${params.TRIGGERED_BY.padRight(40)}║
║  Report:     http://localhost:8090/job/automation-tests/${BUILD_NUMBER}/allure
╚══════════════════════════════════════════════════════╝"""
        }

        unstable {
            echo """
╔══════════════════════════════════════════════════════╗
║  ⚠️  Automation Pipeline UNSTABLE                     ║
║  Some tests FAILED — check Allure report              ║
║  Image Tag: ${params.IMAGE_TAG}
║  This image should NOT be promoted to production      ║
╚══════════════════════════════════════════════════════╝"""
        }

        cleanup {
            cleanWs()
        }
    }
}

// ════════════════════════════════════════════════════════════════
// Helper functions
// ════════════════════════════════════════════════════════════════

def runTestSuite(String suite, String displayName) {
    echo "\n━━━ Running: ${displayName} ━━━"
    sh """
         set +e

            echo "==== Gateway Check ===="
            curl -v http://localhost:8080/actuator/health

            echo
            echo "==== Listening Port ===="
            netstat -an | grep 8080

            echo
            echo "==== Docker Containers ===="
            docker ps

            echo
            echo "Exit code from curl: \$?"

        # cd test-automation
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

def waitForContainer(Map args) {
    def elapsed = 0
    def interval = 5
    echo "⏳ Waiting for ${args.description}..."
    while (elapsed < args.timeoutSecs) {
        def rc = sh(
            script: "docker exec ${args.container} ${args.command} > /dev/null 2>&1",
            returnStatus: true
        )
        if (rc == 0) {
            echo "✅ ${args.description} ready after ${elapsed}s"
            return
        }
        sleep(interval)
        elapsed += interval
    }
    sh "docker logs ${args.container} --tail 20 2>/dev/null || true"
    error("❌ ${args.description} did not become ready within ${args.timeoutSecs}s")
}

def waitForKafka(Map args) {
    def elapsed = 0
    echo "⏳ Waiting for Kafka (this takes ~60-120s on first start)..."
    while (elapsed < args.timeoutSecs) {
        // Check Docker health status — must be exactly 'healthy' not 'unhealthy'/'starting'
        def status = sh(
            script: "docker inspect test-kafka --format '{{.State.Health.Status}}' 2>/dev/null || echo 'unknown'",
            returnStdout: true
        ).trim()
        echo "  Kafka health status: ${status} (${elapsed}/${args.timeoutSecs}s)"
        if (status == 'healthy') {
            echo "✅ Kafka healthy after ${elapsed}s"
            return
        }
        if (elapsed % 30 == 0) {
            sh '''
                echo "===== Kafka Processes ====="
                docker exec test-kafka ps -ef || true

                echo
                echo "===== Listening Ports ====="
                docker exec test-kafka sh -c "netstat -tulpn 2>/dev/null || ss -tulpn" || true

                echo
                echo "===== Last 50 Kafka Logs ====="
                docker logs test-kafka --tail 50 || true
            '''
        }
        if (status == 'unhealthy') {
                echo "Kafka marked unhealthy by Docker"
                dumpKafkaDiagnostics()
                error("Kafka unhealthy")
                }


        }
        sleep(10)
        elapsed += 10
    }
   echo "Kafka never became healthy within timeout"
   dumpKafkaDiagnostics()
   error("Kafka failed to become healthy")


def dumpKafkaDiagnostics(){
 sh '''
               echo
               echo "=================================================="
               echo "KAFKA DEEP DIAGNOSTICS"
               echo "=================================================="

               echo "===== Kafka JVMs ====="
               docker exec test-kafka jps -lv || true

               echo
               echo "===== Kafka Lock ====="
               docker exec test-kafka ls -la /var/lib/kafka/data || true

               echo
               echo "===== Lock owner ====="
               docker exec test-kafka sh -c "fuser /var/lib/kafka/data/.lock || true"
                echo
                echo "===== Generated kafka.properties ====="
                docker exec test-kafka cat /etc/kafka/kafka.properties || true

                echo "===== Server Log Directory ====="
                docker exec test-kafka ls -la /var/log/kafka || true

                echo
                echo "===== Kafka Server Logs ====="
                docker exec test-kafka sh -c '
                for f in /var/log/kafka/*; do
                  echo "===== $f ====="
                  cat "$f"
                done
                '
                docker exec test-kafka bash -c "</dev/tcp/localhost/29092"
                docker exec test-kafka \
                find /var/log/kafka -type f -exec tail -100 {} \;

                echo
                echo "===== RUN SCRIPT ====="
                docker exec test-kafka cat /etc/confluent/docker/run || true

                echo
                echo "===== CONFIGURE SCRIPT ====="
                docker exec test-kafka cat /etc/confluent/docker/configure || true

                echo
                echo "===== ENSURE SCRIPT ====="
                docker exec test-kafka cat /etc/confluent/docker/ensure || true

                docker inspect test-kafka --format '{{.State.ExitCode}}'
                docker inspect test-kafka --format '{{.State.Error}}'

                docker inspect test-kafka --format '{{.RestartCount}}'

                echo
                echo "===== Effective Listeners ====="
                docker exec test-kafka grep -E "^(listeners|advertised.listeners|listener.security.protocol.map|inter.broker.listener.name|zookeeper.connect)" /etc/kafka/kafka.properties || true
                echo
                echo "===== ZOOKEEPER HEALTH ====="
                docker logs test-zookeeper --tail 100

                echo
                echo "===== ZOOKEEPER RUOK ====="
                docker exec test-zookeeper sh -c "echo ruok | nc localhost 2181" || true

                echo
                echo "===== KAFKA LISTENERS ====="
                docker exec test-kafka env | grep LISTENER

                echo
                echo "===== KAFKA PORT ====="
                docker exec test-kafka sh -c "nc -z localhost 9092; echo \$?"

               echo
               echo "===== Container State ====="
               docker inspect test-kafka --format '{{json .State}}' || true

               echo
               echo "===== Configured Healthcheck ====="
               docker inspect test-kafka --format '{{json .Config.Healthcheck}}' || true

               echo
               echo "===== Runtime Health ====="
               docker inspect test-kafka --format '{{json .State.Health}}' || true

               echo
               echo "===== Healthcheck Logs ====="
               docker inspect test-kafka --format '{{json .State.Health.Log}}' || true

               echo
               echo "===== Running Processes ====="
               docker exec test-kafka ps -ef || true

               echo
               echo "===== Java Processes ====="
               docker exec test-kafka pgrep -af java || true

               echo
               echo "===== Listening Ports ====="
               docker exec test-kafka sh -c "netstat -tulpn 2>/dev/null || ss -tulpn" || true

               echo
               echo "===== Kafka Port Check ====="
               docker exec test-kafka sh -c "nc -z localhost 9092; echo KAFKA_PORT_EXIT_CODE=$?" || true

               echo
               echo "===== Zookeeper Connectivity ====="
               docker exec test-kafka sh -c "nc -z test-zookeeper 2181; echo ZK_EXIT_CODE=$?" || true

               echo
               echo "===== Environment Variables ====="
               docker exec test-kafka env | sort || true

               echo
               echo "===== Restart Information ====="
               docker inspect test-kafka --format '
               RestartCount={{.RestartCount}}
               Status={{.State.Status}}
               StartedAt={{.State.StartedAt}}
               FinishedAt={{.State.FinishedAt}}
               ' || true

               echo
               echo "===== Last 200 Kafka Logs ====="
               docker logs test-kafka --tail 200 || true
               docker exec test-kafka bash -c "
               set -x

               echo '===== Kafka start script ====='
               tail -100 /etc/confluent/docker/run

               echo
               echo '===== Configure script ====='
               tail -200 /etc/confluent/docker/configure
               "
               docker exec test-kafka bash -x /etc/confluent/docker/run
               docker exec test-kafka env | sort
               docker exec test-kafka cat /etc/kafka/kafka.properties
               echo
               echo "===== run script ====="
               docker exec test-kafka sed -n '1,250p' /etc/confluent/docker/run || true

               echo
               echo "===== ensure script ====="
               docker exec test-kafka sed -n '1,250p' /etc/confluent/docker/ensure || true

               echo
               echo "===== configure script ====="
               docker exec test-kafka sed -n '1,300p' /etc/confluent/docker/configure || true

               echo
               echo "===== ENTRYPOINT ====="
               docker inspect test-kafka --format '{{json .Config.Entrypoint}}'

               echo "===== ENTRYPOINT 1====="
               docker exec test-kafka cat /etc/confluent/docker/run

               echo
               echo "===== CONFIGURE SCRIPT ====="
               docker exec test-kafka head -200 /etc/confluent/docker/configure

               echo
               echo "===== LAUNCH SCRIPT ====="
               docker exec test-kafka head -200 /etc/confluent/docker/launch

               echo
               echo "===== CMD ====="
               docker inspect test-kafka --format '{{json .Config.Cmd}}'
               docker exec test-kafka bash -c "
               ps -ef --forest
               "
               docker exec test-kafka bash -c "
               ps -ef
               echo
               pstree -ap
               "

               docker exec test-kafka bash -c "grep KAFKA_ /etc/confluent/docker/configure"
               docker inspect test-kafka --format='{{json .Config.Env}}'

               echo
               echo "=================================================="
               echo "END KAFKA DIAGNOSTICS"
               echo "=================================================="
           '''

            error("❌ Kafka became unhealthy")

}
def waitForHttp(Map args) {
    def elapsed = 0
    def interval = 10
    echo "⏳ Waiting for ${args.description} at ${args.url}..."
    while (elapsed < args.timeoutSecs) {
        def response = sh(
            script: "curl -s --max-time 5 ${args.url} 2>/dev/null || echo 'CURL_FAILED'",
            returnStdout: true
        ).trim()
        if (response.contains('UP') || response.contains('"status":"UP"')) {
            echo "✅ ${args.description} is UP after ${elapsed}s"
            return
        }
        sleep(interval)
        elapsed += interval
        // Print first 200 chars of response for debugging
        def preview = response.length() > 200 ? response.substring(0, 200) : response
        echo "  ${args.description} not ready yet (${elapsed}/${args.timeoutSecs}s) response: ${preview}"
    }
      echo "==== ${args.description} diagnostics ===="

        sh """
            docker ps -a

               docker logs test-kafka --tail 200 || true
               docker logs test-user-service --tail 200 || true
               docker logs test-product-service --tail 200 || true
               docker logs test-order-service --tail 200 || true
               docker logs test-payment-service --tail 200 || true
               docker logs test-api-gateway --tail 200 || true

                echo
                   echo "===== PORT CHECK ====="

                   docker exec test-user-service sh -c "netstat -tlnp 2>/dev/null || ss -tlnp" || true
                   docker exec test-product-service sh -c "netstat -tlnp 2>/dev/null || ss -tlnp" || true
                   docker exec test-order-service sh -c "netstat -tlnp 2>/dev/null || ss -tlnp" || true
                   docker exec test-payment-service sh -c "netstat -tlnp 2>/dev/null || ss -tlnp" || true
                   docker exec test-api-gateway sh -c "netstat -tlnp 2>/dev/null || ss -tlnp" || true

                echo
                echo "===== OOM CHECK ====="

                docker inspect test-user-service --format='{{.State.OOMKilled}}' || true
                docker inspect test-product-service --format='{{.State.OOMKilled}}' || true
                docker inspect test-order-service --format='{{.State.OOMKilled}}' || true
                docker inspect test-payment-service --format='{{.State.OOMKilled}}' || true
                docker inspect test-api-gateway --format='{{.State.OOMKilled}}' || true
               echo
               docker stats --no-stream || true

               echo
               free -h || true
        """

        if (args.url.contains("8081")) {
            sh "docker logs test-user-service --tail 300 || true"
        } else if (args.url.contains("8082")) {
            sh "docker logs test-product-service --tail 300 || true"
        } else if (args.url.contains("8083")) {
            sh "docker logs test-order-service --tail 300 || true"
        } else if (args.url.contains("8084")) {
            sh "docker logs test-payment-service --tail 300 || true"
        } else if (args.url.contains("8090")) {
            sh "docker logs test-api-gateway --tail 300 || true"
        }
        echo "==== Order Service Diagnostics ===="

        sh '''
        docker ps -a

        echo
        docker inspect test-order-service --format '{{json .State.Health}}' || true

        echo
        docker logs test-order-service --tail 500 || true

        echo
        curl -v http://localhost:8083/actuator/health || true
        '''

sh '''
echo "===== OOM CHECK ====="
docker inspect test-user-service --format '{{.State.OOMKilled}}' || true
docker inspect test-product-service --format '{{.State.OOMKilled}}' || true
docker inspect test-order-service --format '{{.State.OOMKilled}}' || true
docker inspect test-payment-service --format '{{.State.OOMKilled}}' || true
docker inspect test-api-gateway --format '{{.State.OOMKilled}}' || true
'''
    error("❌ ${args.description} did not become healthy within ${args.timeoutSecs}s")
}
