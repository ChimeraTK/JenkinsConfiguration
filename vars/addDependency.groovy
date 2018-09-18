def call(String projectName) {
  copyArtifacts filter: '**/*', fingerprintArtifacts: true, projectName: "${projectName}", selector: lastSuccessful(), target: 'artefacts'
}
