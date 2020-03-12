import groovy.json.JsonSlurper
import org.apache.commons.httpclient.HttpClient
import org.apache.commons.httpclient.methods.GetMethod
import org.apache.commons.httpclient.methods.DeleteMethod


def provision(playbook) {
    stage "Provision"
    env.PYTHONUNBUFFERED = 1
    sh "ansible-playbook /vagrant/ansible/${playbook} \
        -i /vagrant/ansible/hosts/prod"
}

def buildTests(serviceName, registryIpPort) {
    stage "Build tests"
    def tests = docker.image("${registryIpPort}/${serviceName}-tests")
    try {
        tests.pull()
    } catch(e) {}
    sh "docker build -t \"${registryIpPort}/${serviceName}-tests\" \
        -f Dockerfile.test ."
    tests.push()
}

def runTests(serviceName, target, extraArgs) {
    stage "Run ${target} tests"
    sh "docker-compose -f docker-compose-dev.yml \
        run --rm ${extraArgs} ${target}"
}

def buildService(serviceName, registryIpPort) {
    stage "Build service"
    def service = docker.image("${registryIpPort}/${serviceName}")
    try {
        service.pull()
    } catch(e) {}
    docker.build "${registryIpPort}/${serviceName}"
    service.push()
}

def deploy(serviceName, prodIp) {
    stage "Deploy"
    withEnv(["DOCKER_HOST=tcp://${prodIp}:2375"]) {
        sh "docker-compose pull app"
        sh "docker-compose -p ${serviceName} up -d app"
    }
}

def deployBG(serviceName, prodIp, color) {
    stage "Deploy"
    withEnv(["DOCKER_HOST=tcp://${prodIp}:2375"]) {
        sh "docker-compose pull app-${color}"
        sh "docker-compose -p ${serviceName} up -d app-${color}"
    }
}

def deploySwarm(serviceName, swarmIp, color, instances) {
    stage "Deploy"
    withEnv(["DOCKER_HOST=tcp://${swarmIp}:2375"]) {
        try {
            sh "docker network create --driver overlay ${serviceName}-nw"
        } catch (e) {}
        try {
            sh "docker service rm ${serviceName}-db"
            sh "docker service rm ${serviceName}-${color}"
        } catch (e) {}
        sh "docker service create --name ${serviceName}-db --network ${serviceName}-nw --publish 27017:27017 --reserve-cpu=0.5 --reserve-memory=1GB mongo"
        sh "docker service create --name ${serviceName}-${color} --network ${serviceName}-nw --publish 0:8080 --env SERVICE_NAME=${serviceName}-${color} --env DB_HOST=${serviceName}-db 10.100.198.200:5000/${serviceName}"
        def port = sh(script: "docker service inspect --format=\"{{(index (index .Endpoint.Ports) 0).PublishedPort}}\" ${serviceName}-${color}", returnStdout: true)

        setPort("${serviceName}-${color}", swarmIp, port.trim())

        // Not sure why this line isn't working
        // sh "docker service scale ${serviceName}-${color}=${instances}"
    }
    // putInstances(serviceName, swarmIp, instances)
}

def updateProxy(serviceName, proxyNode) {
    stage "Update proxy"
    stash includes: 'nginx-*', name: 'nginx'
    node(proxyNode) {
        unstash 'nginx'
        sh "sudo cp nginx-includes.conf /data/nginx/includes/${serviceName}.conf"
        sh "sudo consul-template \
            -consul localhost:8500 \
            -template \"nginx-upstreams.ctmpl:/data/nginx/upstreams/${serviceName}.conf:docker kill -s HUP nginx\" \
            -once"
    }
}

def runBGPreIntegrationTests(serviceName, prodIp, color) {
    stage "Run pre-integration tests"
    def port = getPort("${serviceName}-${color}", prodIp)
    try {
        runTests(serviceName, "integ", "-e DOMAIN=http://${prodIp}:${port}")
    } catch(e) {
        stopBG(serviceName, prodIp, color);
        error("Pre-integration tests failed")
    }
}

def runBGPostIntegrationTests(serviceName, prodIp, proxyIp, proxyNode, currentColor, nextColor) {
    // stage "Run post-integration tests"
    // try {
    //     runTests(serviceName, "integ", "-e DOMAIN=http://${proxyIp}")
    // } catch(e) {
    //     if (currentColor != "") {
    //         updateBGProxy(serviceName, proxyNode, currentColor)
    //     }
    //     stopBG(serviceName, prodIp, nextColor);
    //     error("Post-integration tests failed")
    // }
    stopBGNew(serviceName, prodIp, currentColor, nextColor);
}

def stopBG(serviceName, prodIp, color) {
    // if (color.length() > 0) {
    //     stage "Stop"
    //     withEnv(["DOCKER_HOST=tcp://${prodIp}:2375"]) {
    //         sh "docker-compose -p ${serviceName} stop app-${color}"
    //     }
    // }
}

def stopBGNew(serviceName, prodIp, currentColor, nextColor) {
    stage "Stop"
    withEnv(["DOCKER_HOST=tcp://${prodIp}:2375"]) {
        if (nextColor.length() > 0) {
            sh "docker service rm ${serviceName}-${nextColor}"
        }
        sh "docker service update --publish-rm 0:8080 ${serviceName}-${currentColor}"
        sh "docker service update --publish-add 80:8080 ${serviceName}-${currentColor}"
        sh "curl -X PUT -d ${currentColor} http://localhost:8500/v1/kv/${serviceName}/color"
    }
}

def updateBGProxy(serviceName, proxyNode, color) {
    // stage "Update proxy"
    // stash includes: 'nginx-*', name: 'nginx'
    // node(proxyNode) {
    //     unstash 'nginx'
    //     sh "sudo cp nginx-includes.conf /data/nginx/includes/${serviceName}.conf"
    //     sh "sudo consul-template \
    //         -consul localhost:8500 \
    //         -template \"nginx-upstreams-${color}.ctmpl:/data/nginx/upstreams/${serviceName}.conf:docker kill -s HUP nginx\" \
    //         -once"
    //     sh "curl -X PUT -d ${color} http://localhost:8500/v1/kv/${serviceName}/color"
    // }
}

def getCurrentColor(serviceName, prodIp) {
    try {
        return sendHttpRequest("http://${prodIp}:8500/v1/kv/${serviceName}/color?raw")
    } catch(e) {
        return ""
    }
}

def getNextColor(currentColor) {
    if (currentColor == "blue") {
        return "green"
    } else {
        return "blue"
    }
}

def getPort(serviceName, prodIp) {
    // def response = sendHttpRequest("http://${prodIp}:8500/v1/catalog/service/${serviceName}-${color}")
    // def result = new JsonSlurper().parseText(response)[0]
    // return result.ServiceAddress + ":" + result.ServicePort
    return sendHttpRequest("http://${prodIp}:8500/v1/kv/${serviceName}/port?raw")
}

def setPort(serviceName, prodIp, port) {
    sh "curl -X PUT -d ${port} ${prodIp}:8500/v1/kv/${serviceName}/port"
}

def sendHttpRequest(url) {
    def get = new GetMethod(url)
    new HttpClient().executeMethod(get)
    def response = get.getResponseBody()
    get.releaseConnection()
    return new String(response)
}

def sendHttpDeleteRequest(url) {
    def delete = new DeleteMethod(url)
    new HttpClient().executeMethod(delete)
    def response = delete.getResponseBody()
    get.releaseConnection()
    return new String(response)
}

def updateChecks(serviceName, swarmNode) {
    stage "Update checks"
    stash includes: 'consul_check.ctmpl', name: 'consul-check'
    node(swarmNode) {
        unstash 'consul-check'
        sh "sudo consul-template -consul localhost:8500 \
            -template 'consul_check.ctmpl:/data/consul/config/${serviceName}.json:killall -HUP consul' \
            -once"
    }
}

def getInstances(serviceName, swarmIp) {
    return sendHttpRequest("http://${swarmIp}:8500/v1/kv/${serviceName}/instances?raw")
}

def putInstances(serviceName, swarmIp, instances) {
    sh "curl -X PUT -d ${instances} \
        ${swarmIp}:8500/v1/kv/${serviceName}/instances"
}

return this;
