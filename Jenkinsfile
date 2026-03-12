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

    options {
        timeout(time: 45, unit: 'MINUTES')
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
        BASE_URL      = "http://localhost:8080"
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

                    services.each { svc ->
                        def specificImage = "${REGISTRY}/amazon-${svc}:${IMAGE_TAG}"
                        def rc = sh(
                            script: "docker pull ${specificImage} > /dev/null 2>&1",
                            returnStatus: true
                        )
                        if (rc == 0) {
                            resolvedTags[svc] = IMAGE_TAG
                            echo "✅ ${svc}: using tag ${IMAGE_TAG} (just built)"
                        } else {
                            def latestRc = sh(
                                script: "docker pull ${REGISTRY}/amazon-${svc}:latest > /dev/null 2>&1",
                                returnStatus: true
                            )
                            if (latestRc == 0) {
                                resolvedTags[svc] = 'latest'
                                echo "⏩ ${svc}: tag ${IMAGE_TAG} not found — using :latest"
                            } else {
                                error("❌ ${svc}: neither :${IMAGE_TAG} nor :latest found on Docker Hub. Run a full build first.")
                            }
                        }
                    }

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
                            postgres redis zookeeper kafka zipkin
                    """

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
                    waitForKafka(timeoutSecs: 90)
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

                    // Wait for each service's Spring Boot health endpoint
                    def services = [
                        [container: 'test-user-service',    port: 8081, name: 'User Service'],
                        [container: 'test-product-service', port: 8082, name: 'Product Service'],
                        [container: 'test-order-service',   port: 8083, name: 'Order Service'],
                        [container: 'test-payment-service', port: 8084, name: 'Payment Service'],
                        [container: 'test-api-gateway',     port: 8080, name: 'API Gateway'],
                    ]

                    services.each { svc ->
                        waitForHttp(
                            url: "http://localhost:${svc.port}/actuator/health",
                            timeoutSecs: 120,
                            description: svc.name
                        )
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
                    cd test-automation 2>/dev/null || true
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

        stage('Auth & User Tests') {
            steps { runTestSuite('testng-auth-user.xml', 'Auth and User Tests') }
            post { always { collectTestResults() } }
        }

        stage('Product & Order Tests') {
            steps { runTestSuite('testng-product-order.xml', 'Product and Order Tests') }
            post { always { collectTestResults() } }
        }

        stage('Kafka Event Tests') {
            steps { runTestSuite('testng-kafka.xml', 'Kafka Event Tests') }
            post { always { collectTestResults() } }
        }

        stage('DB Validation Tests') {
            steps { runTestSuite('testng-db.xml', 'Database Validation Tests') }
            post { always { collectTestResults() } }
        }

        stage('Resilience4j Tests') {
            steps { runTestSuite('testng-resilience.xml', 'Resilience4j Tests') }
            post { always { collectTestResults() } }
        }

        stage('E2E Tests') {
            when {
                not { expression { params.SKIP_E2E } }
            }
            steps { runTestSuite('testng-e2e.xml', 'End-to-End Tests') }
            post { always { collectTestResults() } }
        }

        // ── Stage 11: Allure Report ───────────────────────────────
        // Always run this — even if tests failed.
        // A failing test is only useful if you can see WHY it failed.
        stage('Generate Report') {
            steps {
                allure([
                    includeProperties: true,
                    reportBuildPolicy: 'ALWAYS',
                    results: [[path: 'test-automation/target/allure-results']]
                ])
                echo "📊 Allure report: http://localhost:8090/job/automation-tests/${BUILD_NUMBER}/allure"
            }
        }
    }

    // ── Post actions ─────────────────────────────────────────────
    // post {} runs AFTER all stages, in ALL cases (success/failure/aborted)
    post {

        // always {} runs no matter what
        always {
            script {
                // Stop and remove all test containers
                // WHY always? If you only stop on success, failed builds
                // leave containers running → next build fails with port conflicts
                sh """
                    export TAG_USER_SERVICE=${env.TAG_USER_SERVICE ?: 'latest'}
                    export TAG_PRODUCT_SERVICE=${env.TAG_PRODUCT_SERVICE ?: 'latest'}
                    export TAG_ORDER_SERVICE=${env.TAG_ORDER_SERVICE ?: 'latest'}
                    export TAG_PAYMENT_SERVICE=${env.TAG_PAYMENT_SERVICE ?: 'latest'}
                    export TAG_NOTIFICATION_SERVICE=${env.TAG_NOTIFICATION_SERVICE ?: 'latest'}
                    export TAG_API_GATEWAY=${env.TAG_API_GATEWAY ?: 'latest'}
                    docker-compose -f ${COMPOSE_FILE} down -v --remove-orphans 2>/dev/null || true
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

        failure {
            script {
                // On failure, dump service logs — essential for debugging
                echo "=== Dumping service logs for debugging ==="
                ['test-user-service','test-product-service','test-order-service',
                 'test-payment-service','test-api-gateway','test-kafka'].each { container ->
                    sh "docker logs ${container} --tail 30 2>/dev/null || echo '(${container} not running)'"
                }
            }
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
        cd test-automation
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
          testResults: 'test-automation/target/surefire-reports/TEST-*.xml'
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
    echo "⏳ Waiting for Kafka (this takes ~30-60s on first start)..."
    while (elapsed < args.timeoutSecs) {
        def rc = sh(
            script: "docker exec test-kafka kafka-topics --bootstrap-server kafka:29092 --list > /dev/null 2>&1",
            returnStatus: true
        )
        if (rc == 0) {
            echo "✅ Kafka ready after ${elapsed}s"
            return
        }
        sleep(10)
        elapsed += 10
        echo "  Still waiting for Kafka... (${elapsed}/${args.timeoutSecs}s)"
    }
    sh "docker logs test-kafka --tail 30 2>/dev/null || true"
    error("❌ Kafka did not start within ${args.timeoutSecs}s")
}

def waitForHttp(Map args) {
    def elapsed = 0
    def interval = 10
    echo "⏳ Waiting for ${args.description} at ${args.url}..."
    while (elapsed < args.timeoutSecs) {
        def rc = sh(
            script: "curl -sf ${args.url} | grep -q UP 2>/dev/null",
            returnStatus: true
        )
        if (rc == 0) {
            echo "✅ ${args.description} is UP after ${elapsed}s"
            return
        }
        sleep(interval)
        elapsed += interval
        echo "  ${args.description} not ready yet (${elapsed}/${args.timeoutSecs}s)"
    }
    error("❌ ${args.description} did not become healthy within ${args.timeoutSecs}s")
}
