import { check, sleep } from 'k6'
import exec from 'k6/execution';
import http from 'k6/http';
import {Gauge} from 'k6/metrics';

// Parameters
const roleCount = Number(__ENV.ROLE_COUNT)
const userCount = Number(__ENV.USER_COUNT)
const vus = Math.min(1, userCount, roleCount)

// Option setting
const baseUrl = __ENV.BASE_URL
const username = __ENV.USERNAME
const password = __ENV.PASSWORD

// Option setting
export const options = {
    insecureSkipTLSVerify: true,

    setupTimeout: '8h',

    scenarios: {
        createClusterRole: {
            executor: 'shared-iterations',
            exec: 'createClusterRole',
            vus: vus,
            iterations: roleCount,
            maxDuration: '1h',
        },
        updateClusterRole: {
            executor: 'shared-iterations',
            exec: 'updateClusterRole',
            vus: vus,
            iterations: roleCount,
            maxDuration: '1h',
        },
        deleteFinalizers: {
            executor: 'shared-iterations',
            exec: 'deleteFinalizers',
            vus: vus,
            iterations: roleCount,
            maxDuration: '1h',
        },
        checkFinalizers: {
            executor: 'shared-iterations',
            exec: 'checkFinalizers',
            vus: vus,
            iterations: roleCount,
            maxDuration: '1h',
        },
    },
    thresholds: {
        checks: ['rate>0.99']
    }
}


// Test functions, in order of execution
export function setup() {
    // log in
    const res = http.post(`${baseUrl}/v3-public/localProviders/local?action=login`, JSON.stringify({
        "description": "UI session",
        "responseType": "cookie",
        "username": username,
        "password": password
    }))

    check(res, {
        '/v3-public/localProviders/local?action=login returns status 200': (r) => r.status === 200,
    })

    const cookies = http.cookieJar().cookiesForURL(res.url)

    // delete leftovers, if any
    cleanup(cookies)

    return cookies
}

function cleanup(cookies) {
    let res = http.get(`${baseUrl}/v1/rbac.authorization.k8s.io.clusterroles`, {cookies: cookies})
    check(res, {
        '/v1/rbac.authorization.k8s.io.clusterroles returns status 200': (r) => r.status === 200
    })
    JSON.parse(res.body)["data"].filter(r => ("id" in r) && r["id"].startsWith('Test')).forEach(r => {
        let res2 = http.del(`${baseUrl}/v1/rbac.authorization.k8s.io.clusterrole/${r["id"]}`, {cookies: cookies})
        check(res2, {
            'DELETE /v1/rbac.authorization.k8s.io.clusterroles returns status 200': (r) => r.status === 200,
        })
    })
}


export function createClusterRole(cookies) {
    const i = exec.scenario.iterationInTest

    const res = http.post(
        `${baseUrl}/v1/rbac.authorization.k8s.io.clusterroles`,
        JSON.stringify({
            "type": "rbac.authorization.k8s.io.clusterrole",
            "description": "Test Cluster Role",
            "metadata":{"name":`TestClusterRole${i}`},
            "rules":[{"apiGroups":[""],"nonResourceURLs":[],"resourceNames":[],"resources":["pods"],"verbs":["get"]}],
            "undefined":false
        }),
        { cookies: cookies }
    )

    check(res, {
        'v1/rbac.authorization.k8s.io.clusterroles post returns status 201': (r) => r.status === 201,
    })
}


export function updateClusterRole(cookies) {
    const i = exec.scenario.iterationInTest

    sleep(2)

    const response = http.get(
        `${baseUrl}/v1/rbac.authorization.k8s.io.clusterrole/TestClusterRole${i}`,
        {cookies: cookies}
    )
    check(response, {
        'v1/rbac.authorization.k8s.io.clusterrole get returns status 200': (r) => r.status === 200,
    })

    const resourceVersion = JSON.parse(response.body)["metadata"]["resourceVersion"]

    const res = http.put(
        `${baseUrl}/v1/rbac.authorization.k8s.io.clusterrole/TestClusterRole${i}`,
        JSON.stringify({
            "metadata":{"name":`TestClusterRole${i}`,"resourceVersion":`${resourceVersion}`,"annotations":{"cluster.cattle.io/name":"test", "cluster.cattle.io/namespace":"test"}},
            "rules":[{"apiGroups":[""],"nonResourceURLs":[],"resourceNames":[],"resources":["pods"],"verbs":["get"]}],
        }),
        { cookies: cookies }
    )

    check(res, {
        'v1/rbac.authorization.k8s.io.clusterrole put returns status 200': (r) => r.status === 200,
    })
}

export function deleteFinalizers(cookies) {
    const i = exec.scenario.iterationInTest

    sleep(4)

    const response = http.get(
        `${baseUrl}/v1/rbac.authorization.k8s.io.clusterrole/TestClusterRole${i}`,
        {cookies: cookies}
    )
    check(response, {
        'finalizer delete get returns status 200': (r) => r.status === 200,
    })

    const resourceVersion = JSON.parse(response.body)["metadata"]["resourceVersion"]

    const res = http.put(
        `${baseUrl}/v1/rbac.authorization.k8s.io.clusterrole/TestClusterRole${i}`,
        JSON.stringify({
            "metadata":{"name":`TestClusterRole${i}`,"resourceVersion":`${resourceVersion}`,"annotations":{}},
            "rules":[{"apiGroups":[""],"nonResourceURLs":[],"resourceNames":[],"resources":["pods"],"verbs":["get"]}],
        }),
        { cookies: cookies }
    )

    check(res, {
        'finalizer overwrite put returns status 200': (r) => r.status === 200,
    })

    // this should fail
    /*check(res, {
        'finalizers do not include wrangler.cattle.io/auth-prov-v2-crole': (r) => JSON.parse(r.body)["metadata"]["finalizers"][0] == []
    })*/
}

export function checkFinalizers(cookies) {
    const i = exec.scenario.iterationInTest

    sleep(6)

    const response = http.get(
        `${baseUrl}/v1/rbac.authorization.k8s.io.clusterrole/TestClusterRole${i}`,
        {cookies: cookies}
    )
    check(response, {
        'check finalizer get returns status 200': (r) => r.status === 200,
    })

    check(response, {
        'finalizers include wrangler.cattle.io/auth-prov-v2-crole': (r) => JSON.parse(r.body)["metadata"]["finalizers"][0] == ["wrangler.cattle.io/auth-prov-v2-crole"]
    })
}

//TODO: Fix broken cleanup
export function deleteClusterRoles(cookies) {
    
    sleep(2)

    const i = exec.scenario.iterationInTest

    const response = http.del(
        `${baseUrl}/v1/rbac.authorization.k8s.io.clusterrole/TestClusterRole${i}`,
        {cookies: cookies}
    )
    check(response, {
        'delete finalizer get returns status 200': (r) => r.status === 200,
    })

    /*const i = exec.scenario.iterationInTest
   
    let res = http.del(`${baseUrl}/v1/rbac.authorization.k8s.io.clusterroles/TestClusterRole${i}`, {cookies: cookies})
    check(res, {
        'DELETE /v1/rbac.authorization.k8s.io.clusterroles returns status 200': (r) => r.status === 200,
    })*/
    
    /*for (let k = roleCount-1;k > 0; k--) {
        let res = http.del(`${baseUrl}/v1/rbac.authorization.k8s.io.clusterrole/TestClusterRole${k}`, {cookies: cookies})
        check(res, {
            'DELETE /v1/rbac.authorization.k8s.io.clusterroles returns status 200': (r) => r.status === 200,
        })
    }*/

    /*let res = http.get(`${baseUrl}/v1/rbac.authorization.k8s.io.clusterroles`, {cookies: cookies})
    check(res, {
        '/v1/rbac.authorization.k8s.io.clusterroles returns status 200': (r) => r.status === 200
    })
    JSON.parse(res.body)["data"].filter(r => ("id" in r) && r["id"].startsWith('Test')).forEach(r => {
        let res2 = http.del(`${baseUrl}/v1/rbac.authorization.k8s.io.clusterrole/${r["id"]}`, {cookies: cookies})
        check(res2, {
            'DELETE /v1/rbac.authorization.k8s.io.clusterroles returns status 200': (r) => r.status === 200,
        })
    })*/

}