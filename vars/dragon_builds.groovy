/*
 * Define the global constant as a function, because it is totally unclear to me how to do this as a variable in groovy.
 * It will always not find it although the same syntax has worked in different context.
 */
def getBuilds() {
    return ['noble-debug',
            'noble-release',
            /*'noble-tsan',
            'noble-asan',*/
            'bookworm-debug',
            'bookworm-release'/*,
            'tumbleweed-debug',
            'tumbleweed-release',
            'noble-tag'*/]
}

def getArtefactsDir() {
    return '/home/msk_jenkins/dragon-artefacts'
}
