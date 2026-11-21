// Azure hosting for the Android app's release builds (APK download links), one per environment.
//
// The app itself talks to the existing Song Book API on App Service (Song-book-Webapp/infra/main.bicep);
// nothing in that deployment changes. This template only adds a small storage account whose
// `releases` container is publicly readable per blob (no listing), so testers and members can install
// the APK from a link. The account holding book PDFs stays private and separate.
//
// Deploy (same resource group as the web environment, so it shares lifecycle and tags):
//   az deployment group create -g <rg> -f infra/releases.bicep \
//     -p environmentName=dev deployPrincipalId=<object id of the GitHub OIDC service principal>

@allowed(['dev', 'prod'])
param environmentName string

param location string = resourceGroup().location

param appName string = 'songbook'

@description('Object id of the service principal GitHub Actions logs in as (OIDC). Gets Storage Blob Data Contributor on this account only. Leave empty to assign it manually.')
param deployPrincipalId string = ''

var tags = { app: appName, environment: environmentName, component: 'android-releases' }

resource releases 'Microsoft.Storage/storageAccounts@2023-05-01' = {
  name: take('st${appName}apk${environmentName}${uniqueString(resourceGroup().id)}', 24)
  location: location
  tags: tags
  kind: 'StorageV2'
  sku: { name: 'Standard_LRS' }
  properties: {
    accessTier: 'Hot'
    allowBlobPublicAccess: true // anonymous *read* of individual blobs in the releases container only
    allowSharedKeyAccess: false // uploads use Entra ID (GitHub OIDC), never account keys
    minimumTlsVersion: 'TLS1_2'
    supportsHttpsTrafficOnly: true
    defaultToOAuthAuthentication: true
    publicNetworkAccess: 'Enabled'
  }
}

resource blobService 'Microsoft.Storage/storageAccounts/blobServices@2023-05-01' = {
  parent: releases
  name: 'default'
  properties: {
    deleteRetentionPolicy: { enabled: true, days: 7 }
  }
}

resource container 'Microsoft.Storage/storageAccounts/blobServices/containers@2023-05-01' = {
  parent: blobService
  name: 'releases'
  properties: {
    publicAccess: 'Blob' // blobs readable by URL; the container cannot be listed anonymously
  }
}

// Storage Blob Data Contributor
var blobContributorRole = 'ba92f5b4-2d11-453d-a403-e96b0029c9fe'

resource deployerCanUpload 'Microsoft.Authorization/roleAssignments@2022-04-01' = if (!empty(deployPrincipalId)) {
  name: guid(releases.id, deployPrincipalId, blobContributorRole)
  scope: releases
  properties: {
    principalId: deployPrincipalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', blobContributorRole)
  }
}

output storageAccountName string = releases.name
output releasesBaseUrl string = '${releases.properties.primaryEndpoints.blob}releases'
output latestApkUrl string = '${releases.properties.primaryEndpoints.blob}releases/${environmentName}/songbook-${environmentName}-latest.apk'
