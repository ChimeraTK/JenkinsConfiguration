/*
 * Define the global constant as a function, because it is totally unclear to me how to do this as a variable in groovy.
 * It will always not find it although the same syntax has worked in different context.
 */
def getBuilds() {
    return ['focal-debug',
            'focal-release',
            'focal-tsan',
            'focal-asan',
            'noble-debug',
            'tumbleweed-debug',
            'tumbleweed-release']
}

def getArtefactsDir() {
    return '/home/msk_jenkins/dragon-artefacts'
}