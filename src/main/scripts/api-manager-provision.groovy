import groovy.json.JsonSlurper
import groovy.json.JsonOutput 
@Grab( 'org.codehaus.groovy.modules.http-builder:http-builder:0.7.1' )
import groovyx.net.http.*
import groovyx.net.http.ContentType.*
import groovyx.net.http.Method.*
import groovyx.net.http.RESTClient
//import org.apache.http.client.HttpClient

class CICDUtil
{
    static def int WARN=1;
    static def int INFO=2;
    static def int DEBUG=3;
    static def int TRACE=4;
   
    static def logLevel = DEBUG;  //root logger level

    static def log (java.lang.Integer level, java.lang.Object content)
    {
        if (level <= logLevel)
        {
            def logPrefix = new Date().format("YYYYMMdd-HH:mm:ss") 
            if (level == WARN)
            {
                logPrefix += " WARN"
            }
            if (level == INFO)
            {
                logPrefix += " INFO"
            }
            if (level == DEBUG)
            {
                logPrefix += " DEBUG"
            }
            if (level == TRACE)
            {
                logPrefix += " TRACE"
            }
            println logPrefix + " : " + content 
        }

    }
   
    def getAnypointToken(props)
    {
        log(DEBUG,  "START getAnypointToken")

        def username=props.username
        def password=props.password 

        log(TRACE, "username=" + username)
       
        def urlString = "https://anypoint.mulesoft.com/accounts/login"

        def message = 'username='+username+'&password='+password
        
        def headers=["Content-Type":"application/x-www-form-urlencoded", "Accept": "application/json"]

        def connection = doRESTHTTPCall(urlString, "POST", message, headers)

        if ( connection.responseCode =~ '2..') 
        {

        }
        else
        {
            throw new Exception("Failed to get the login token!")
        }

        def response = "${connection.content}"

        def token = new JsonSlurper().parseText(response).access_token

        log(DEBUG,  "END getAnypointToken")

        return token

    }
    
    def init ()
    {

   
              
        def props = ['username':System.properties.'anypoint.username', 
                     'password': System.properties.'anypoint.password',
                     'orgId': System.properties.'orgId',
                     'version': System.properties.'version',
                     'envId': System.properties.'envId',
                     'assetId': System.properties.'assetId',
                     'assetVersion': System.properties.'assetVersion',
                     'path': System.getProperty("user.dir"),
                     'policy': System.properties.'policy',
                     'apiImplUri':System.properties.'apiImplUri',
                     'apiProxyUri':System.properties.'apiProxyUri',
                     'isCloudHub':System.properties.'isCloudHub',
                     'apiType':System.properties.'apiType',
                     'deploymentType':System.properties.'deploymentType', 
                     'apiInstanceLabel':System.properties.'apiInstanceLabel',
                     'basicSlaMaxReq':System.properties.'basicSlaMaxReq',
                     'goldSlaMaxReq':System.properties.'goldSlaMaxReq',
                     'platinumSlaMaxReq':System.properties.'platinumSlaMaxReq',
                     'previousAssetVersion':System.properties.'previousAssetVersion'
                     ]

        log(DEBUG,  "props->" + props)
        return props;
    }


    def provisionAPIManager(props)
    {
        def token = getAnypointToken(props);
        
        def profileDetails = getProfile(token,props);

        def result = getAPIInstanceByExchangeAssetDetail(props, token, profileDetails);
        
        def tierBasic
        def tierGold
        def tierPlatinum
        
        if ( props.policy == "sla" )

        {
            tierBasic = applyTier(token, result.apiDiscoveryId , props , "basic" ,profileDetails) 
            
            tierGold = applyTier(token, result.apiDiscoveryId , props , "gold" ,profileDetails) 
            
            tierPlatinum = applyTier(token, result.apiDiscoveryId , props , "platinum" ,profileDetails) 
            
            def policyDetails = applyPolicy (token, result.apiDiscoveryId , props , "default" ,profileDetails)
            
            //tierIds = ["basic": tierBasic.id, "gold": tierGold.id, "platinum": tierPlatinum.id ]
            
           // log ( INFO , "Tier Codes : "+ tierIds)
           
        }
        
        if ( result.apiVerChange == "true" )
        
        {
        
            def cntrs = getContracts ( token , props , profileDetails , result.oldApiId  )
            def cntrs1 = getContracts ( token , props , profileDetails , result.apiDiscoveryId  )
            
            log(INFO, " Old API Contracts " + cntrs ) 
            log(INFO, " New API Contracts " + cntrs1 )    

            
            if ( cntrs.total > 0 && cntrs1.total == 0 )
            
            {
               cntrs.contracts.each {
            
                        log(INFO, it)
                        
                        def tierId 
                        def tierName
                        
                        if ( it.status == "APPROVED" || it.status == "REVOKED" )
                        {
                            tierName = it.tier.name
                        }
                        else {
                            tierName = it.requestedTier.name
                        }
                        
                        if ( tierName == "Basic" )
                        {
                            tierId = tierBasic.id
                        }
                        if ( tierName == "Gold" )
                        {
                            tierId = tierGold.id
                            
                        }
                        if ( tierName == "Platinum" )
                        {
                            tierId = tierPlatinum.id
                        }
                
                        def pc = postContract ( token , props , profileDetails , result.apiDiscoveryId , it.applicationId , tierId )
                        
                        log ( INFO , "Preparing for Patch call to Revoke the status  " )
                        def payload = /{"status":"REVOKED"}/ 
                        def pth = "/apimanager/api/v1/organizations/"+profileDetails.orgId+"/environments/"+profileDetails.envId+"/apis/"+result.oldApiId+"/contracts/"+it.id
                        
                        patchCall ( token , payload , pth )
                        def del = deleteContract ( token , props , profileDetails , result.oldApiId , it.id )
                        
                       
                        if (  tierName == "Gold" || tierName == "Platinum"  )
                        
                        {
                        
                            log ( INFO , "Preparing for Patch call to Aprove the status  " )
                            
                            payload = /{"status":"APPROVED"}/ 
                            pth = "/apimanager/api/v1/organizations/"+profileDetails.orgId+"/environments/"+profileDetails.envId+"/apis/"+result.apiDiscoveryId+"/contracts/"+pc.id
                            patchCall ( token , payload , pth )
                         
                        }
                        
                        
                      
                        
                       
                  } 
                  
                  deleteApi ( token , profileDetails , result.oldApiId )                      
            
            }
            else {
                
                log (INFO , " No contracts with OldApi" )
            
            }
        
        }
        
        

        log(INFO, "apiInstance=" + result)

        return result
    }
    
    def getProfile ( token , props )
    {
        log(DEBUG,  "START getProfile")
        
        def orgId = props.orgId
        def envId = null
        
        def urlString = "https://anypoint.mulesoft.com/accounts/api/organizations/"+props.orgId+"/environments"
        
        def headers=["Content-Type":"application/json", "Authorization": "Bearer " + token, "Accept": "application/json"]
        
        def connection = doRESTHTTPCall(urlString, "GET", null, headers)
        
        def response = null
        def profDet = null 
        def allEnvIns = null
        def envIns = null
        
        if (connection.responseCode == 200)
            {
            
                log(INFO, " getEnv is successfull! statusCode=" + connection.responseCode)
                response = "${connection.content}"
                profDet = new JsonSlurper().parseText(response)
                //log(INFO, " Profile Details : " + profDet )
                                               
                allEnvIns = profDet.data
                
                allEnvIns.each {    
                
                log(INFO,"For each it : " + it)
                
                if (it.name == props.envId )
                       
                        {
                            envIns = it
                            envId = envIns.id
                            log(INFO, "Matched" + envId)
                        }
                      }
            }
        else
            {
            
                throw new Exception("Failed to get the Env statusCode=${connection.responseCode} responseMessage=${response}")
            
            }
            
       def profileDetails  = ["orgId": orgId, "envId": envId]
       
       log ( INFO , "ProfileDetails" + profileDetails )
       
       log(DEBUG,  "START getProfile")
       
       return profileDetails
    }

    def getAPIInstanceByExchangeAssetDetail(props, token , profileDetails)
    {

        log(DEBUG,  "START getAPIInstanceByExchangeAssetDetail")

        def apiInstance
        def apiDiscoveryName
        def apiDiscoveryVersion
        def apiDiscoveryId
        def oldApiId
        def apiVerChange = "false"
        
        def urlString = "https://anypoint.mulesoft.com/exchange/api/v1/assets/"+profileDetails.orgId+"/"+props.assetId

        def headers=["Content-Type":"application/json", "Authorization": "Bearer " + token, "Accept": "application/json"]

        def connection = doRESTHTTPCall(urlString, "GET", null, headers)

        if (connection.responseCode == 404)
        {
            log(INFO, "API Instance for " + props.assetId + " is not found in API Manager")

        } 
        else if (connection.responseCode == 200)
        {
            log(INFO, "API Instances for " + props.assetId + " has been found in the platform ");

            def response = "${connection.content}"

            def allAPIInstances = new JsonSlurper().parseText(response).instances;
          
            allAPIInstances.each { 
                
                log(INFO, it)
                
               
                if (it.environmentId == profileDetails.envId && it.productAPIVersion == props.version )
                
                { 
                   log(INFO, "Env and API Version Matched" )        
                   
                  if (it.version == props.assetVersion && it.name == props.apiInstanceLabel  ){
                    
                    apiInstance = it;
                    apiDiscoveryName = "groupId:"+profileDetails.orgId+":assetId:"+ props.assetId
                    apiDiscoveryVersion = apiInstance.name
                    apiDiscoveryId = apiInstance.id
                    
                    log ( INFO , "This API Instance matched with the ArtifactID , ArtifactVersion & APIVersion provided : " + apiInstance )
                  }
                  else if ( it.version == props.previousAssetVersion )  {
                    log(INFO, "Previous Asset Version Details Captured" )   
                    apiVerChange = "true"
                    oldApiId = it.id
                  }
                  
                  
                 
                }
                
            }

            log(INFO, "apiInstance for env " + profileDetails.envId + " is " + apiInstance);

        }

        if (apiInstance == null)
        {
            apiInstance = createAPIInstance(token, props , profileDetails)
            
            apiDiscoveryName = apiInstance.autodiscoveryInstanceName
            apiDiscoveryVersion = apiInstance.productVersion
            apiDiscoveryId = apiInstance.id
            
           
         
        }

        def result = ["apiInstance": apiInstance, "apiDiscoveryName": apiDiscoveryName, "apiDiscoveryVersion":apiDiscoveryVersion, "apiDiscoveryId": apiDiscoveryId, "oldApiId": oldApiId, "apiVerChange": apiVerChange]
        
        log(DEBUG,  "END getAPIInstanceByExchangeAssetDetail")

        return result

    }
    
    def applyTier ( token , apiId , props , tname , profileDetails )
    
    {
        log(DEBUG,  "START applyTier :" + tname);
        
        def basic = / { "status": "ACTIVE", "autoApprove": true, "limits": [{ "visible": true, "maximumRequests": 100, "timePeriodInMilliseconds": 3600000 } ], "name": "Basic", "description": "SLA Defenition for Basic Tier Plan" } /
        def gold = / { "status": "ACTIVE", "autoApprove": false, "limits": [{ "visible": true, "maximumRequests": 100, "timePeriodInMilliseconds": 60000 } ], "name": "Gold", "description": "SLA Defenition for Gold Tier Plan" } / 
        def platinum = / { "status": "ACTIVE", "autoApprove": false, "limits": [{ "visible": true, "maximumRequests": 100, "timePeriodInMilliseconds": 1000 } ], "name": "Platinum", "description": "SLA Defenition for Platinum Tier Plan" } / 
        
        def request = null
        
        if ( tname == "basic" )
        {
            request = new JsonSlurper().parseText(basic);
            request.limits[0].maximumRequests = props.basicSlaMaxReq
        } 
        if ( tname == "gold" )
        {
            request = new JsonSlurper().parseText(gold);
            request.limits[0].maximumRequests = props.goldSlaMaxReq
        }
        if ( tname == "platinum" )
        {
            request = new JsonSlurper().parseText(platinum);
            request.limits[0].maximumRequests = props.platinumSlaMaxReq
        }
        
        def message = JsonOutput.toJson(request)
        
        log(INFO, "applyTier request message for Tier : "+ tname + ", Message ->" + message);
        
        def urlString = "https://anypoint.mulesoft.com/apimanager/api/v1/organizations/"+profileDetails.orgId+"/environments/"+profileDetails.envId + "/apis/"+apiId+"/tiers"
        
        def headers=["Content-Type":"application/json", "Authorization": "Bearer " + token, "Accept": "application/json"]
        
        def connection = doRESTHTTPCall(urlString, "POST", message, headers)
        
        def response = null
        
        def tier = null 
        
        if ( connection.responseCode =~ '2..') 
        {
            log(INFO, "the Tier: "+ tname + " is created successfully! statusCode=" + connection.responseCode)
            response = "${connection.content}" 
            tier = new JsonSlurper().parseText(response)
            log(DEBUG, "Tier Details "+ tier )
        }
        else if ( connection.responseCode =~ '409') 
        {
            log(INFO, "The Tier: "+tname + " already exists , cannot overide ! statusCode=" + connection.responseCode)
        }
        
        else
        {
            throw new Exception("Failed to create Tier: "+tname + "  ! statusCode=${connection.responseCode} responseMessage=${response}")
        }
        
        log(DEBUG,  "END applyTier: "+tname)
        
        return tier
        
    }
    
    def applyPolicy (token, apiId , props, name , profileDetails )
    {
        log(DEBUG,  "START applyPolicy :" + name);
        
        def rateLimitSlaPolicy = / {"policyTemplateId": "307","groupId": "68ef9520-24e9-4cf2-b2f5-620025690913","assetId": "rate-limiting-sla-based","assetVersion": "1.1.1","configurationData": { "clientIdExpression": "#[attributes.headers['client_id']]", "clientSecretExpression": "#[attributes.headers['client_secret']]","clusterizable": true,"exposeHeaders": true },"pointcutData": null } /
               
        def clientIdPolicy = /{"policyTemplateId": "294","groupId": "68ef9520-24e9-4cf2-b2f5-620025690913", "assetId": "client-id-enforcement", "assetVersion": "1.1.2", "configurationData": { "credentialsOriginHasHttpBasicAuthenticationHeader":"customExpression","clientIdExpression": "#[attributes.headers['client_id']]","clientSecretExpression": "#[attributes.headers['client_secret']]"}, "pointcutData":null}/ 
        
        def rateLimitPolicy = /{"policyTemplateId": "295","groupId": "68ef9520-24e9-4cf2-b2f5-620025690913","assetId": "rate-limiting","assetVersion": "1.2.1","configuration": {"rateLimits": [{"timePeriodInMilliseconds": null,"maximumRequests": null}], "clusterizable": true, "exposeHeaders": true},"pointcutData":null }/
        
        def oauthPolicy = /{"policyTemplateId": "302","groupId": "68ef9520-24e9-4cf2-b2f5-620025690913", "assetId": "external-oauth2-access-token-enforcement","assetVersion": "1.1.1", "configuration": {"scopes": null,"tokenUrl": null,"exposeHeaders": true }, "pointcutData":null }/
       
       def request = null
        
        if ( name == "clientIdEnforcementPolicy" )
        {  
           request = new JsonSlurper().parseText(clientIdPolicy);     
        }
        
        if ( name == "rateLimitPolicy" )
        {          
           request = new JsonSlurper().parseText(rateLimitPolicy);
          request.configuration.rateLimits[0].timePeriodInMilliseconds = props.timePeriod
          request.configuration.rateLimits[0].maximumRequests = props.maxRequests
        }
        
        if ( name == "oAuthPolicy" )
        {  
           request = new JsonSlurper().parseText(oauthPolicy); 
          request.configuration.scopes = props.scopes
          request.configuration.tokenUrl = props.tokenUrl
        }
        
        if ( name == "default" )
        {  
           request = new JsonSlurper().parseText(rateLimitSlaPolicy); 
        }
        
        def message = JsonOutput.toJson(request)
        
        log(INFO, "applyPolicy request message for Policy : "+name + ", Message ->" + message);

        def urlString = "https://anypoint.mulesoft.com/apimanager/api/v1/organizations/"+profileDetails.orgId+"/environments/"+profileDetails.envId + "/apis/"+apiId+"/policies"
                       
        def headers=["Content-Type":"application/json", "Authorization": "Bearer " + token, "Accept": "application/json"]

        def connection = doRESTHTTPCall(urlString, "POST", message, headers)
          
        def response = null
        
        def policy = null 
        
        if ( connection.responseCode =~ '2..') 
        {
            log(INFO, "the Policy: "+name + " is created successfully! statusCode=" + connection.responseCode)
            response = "${connection.content}" 
            policy = new JsonSlurper().parseText(response)
            log(DEBUG, "Policy Details "+ policy )
        }
        
        else if ( connection.responseCode =~ '409') 
        {
            log(INFO, "The Policy: "+name + " already exists , cannot overide ! statusCode=" + connection.responseCode)
        }
        
        else
        {
            throw new Exception("Failed to create Policy: "+name + "  ! statusCode=${connection.responseCode} responseMessage=${response}")
        }
        
        log(DEBUG,  "END applyPolicy: "+name)
        
        return policy
    
    }


    def createAPIInstance(token, props , profileDetails)
    {
        log(DEBUG,  "START createAPIInstance")

        def apiTemplate = /{ "spec": {"groupId": null,"assetId": null,"version": null},"endpoint": {"uri": null,"proxyUri": null,"isCloudHub": null,"muleVersion4OrAbove": true,"type": null,"deploymentType": null},"instanceLabel": null}/
        
        def request = new JsonSlurper().parseText(apiTemplate);
        request.spec.groupId = profileDetails.orgId
        request.spec.assetId = props.assetId
        request.spec.version = props.assetVersion
        request.endpoint.uri = props.apiImplUri
        
        if( props.apiProxyUri != "null" )
        {
        request.endpoint.proxyUri = props.apiProxyUri
        }
        
        if ( props.isCloudHub == "true" )
        {
          request.endpoint.isCloudHub = true
        }
        else 
        {
          request.endpoint.isCloudHub = false
        }
        
       // request.endpoint.muleVersion4OrAbove = props.muleVersion4OrAbove
        request.endpoint.type = props.apiType 
        request.endpoint.deploymentType = props.deploymentType
        request.instanceLabel = props.apiInstanceLabel

        def message = JsonOutput.toJson(request)
        
        log(INFO, "createAPIInstance request message=" + message);

        def urlString = "https://anypoint.mulesoft.com/apimanager/api/v1/organizations/"+profileDetails.orgId+"/environments/"+profileDetails.envId + "/apis"
        
        def headers=["Content-Type":"application/json", "Authorization": "Bearer " + token, "Accept": "application/json"]
              
        def connection = doRESTHTTPCall(urlString, "POST", message, headers)
            
        def response = "${connection.content}"
          
        if ( connection.responseCode =~ '2..') 
        {
            log(INFO, "the API instance is created successfully! statusCode=" + connection.responseCode)
            
            
        }
        else
        {
            throw new Exception("Failed to create API Instance! statusCode=${connection.responseCode} responseMessage=${response}")
        }
    

        def apiInstance = new JsonSlurper().parseText(response)

        log(DEBUG,  "END createAPIInstance")

        return apiInstance;
    }
    
    
    def postContract ( token , props , profileDetails , apiId , appId , tierId )
    
    {
    
        log ( INFO , " start Post Contracts : " +appId) 
        
        def payload = / {"applicationId":0,"partyId":"","partyName":"","acceptedTerms":false,"requestedTierId":0}  / 
        def request = new JsonSlurper().parseText(payload)
        request.applicationId = appId
        request.requestedTierId = tierId
        def message = JsonOutput.toJson(request)
        log(INFO, "PostContract request message=" + message)
        
        def urlString = "https://anypoint.mulesoft.com/apimanager/api/v1/organizations/"+profileDetails.orgId+"/environments/"+profileDetails.envId + "/apis/"+apiId+"/contracts"
        def headers=["Content-Type":"application/json", "Authorization": "Bearer " + token ]
        def connection = doRESTHTTPCall(urlString, "POST", message, headers)
        def response = "${connection.content}" 
               
        if ( connection.responseCode =~ '2..') 
        {
            
            //contractId = new JsonSlurper().parseText(response).id
            log(INFO, " Contract : Posted successfully. statusCode=${connection.responseCode}")
            
        }
        else {
        
            throw new Exception("Failed to post the contract : ${appId} ! statusCode=${connection.responseCode} responseMessage=${response}")
        }
        
        def contract = new JsonSlurper().parseText(response)
        log ( INFO , " end PostContract  : "+appId) 
        return contract
    }



    def patchCall ( token , payload , pth )
    
    {
    
                           log ( INFO , "Start Patch call ")
                           def py = new JsonSlurper().parseText(payload)
                           def msg =  JsonOutput.toJson(py)
                           log ( INFO , "message for the patch call : " + msg )
                           def rest = new RESTClient("https://anypoint.mulesoft.com/")
                           def auth = "Bearer "+token
                           log( INFO , "Path for the patch call : " + pth )
                           def res = rest.patch( path : pth , headers : [ Authorization : auth ]  , body : msg , requestContentType : 'application/json' )
                           
                           log( INFO , "Response Status : " + res.status )
                           log( INFO , "Response Status : " + res.data )
                           log ( INFO , "End Patch call ")
    }
    
    def deleteContract ( token , props , profileDetails , apiId , contractId )
    
    {
    
        log ( INFO , " start Delete Contracts " ) 
       
        def urlString = "https://anypoint.mulesoft.com/apimanager/api/v1/organizations/"+profileDetails.orgId+"/environments/"+profileDetails.envId + "/apis/"+apiId+"/contracts/"+contractId
        def headers=["Content-Type":"application/json", "Authorization": "Bearer " + token ]
        def connection = doRESTHTTPCall(urlString, "DELETE", null, headers)
        
        
        if ( connection.responseCode =~ '2..') 
        {
          log(INFO, " Contract Deleted successfully. statusCode=${connection.responseCode}")
            
        }
        else {
        
            throw new Exception("Failed to delete the contract : ${appId} ! statusCode=${connection.responseCode} ")
        }
        
        log ( INFO , " end Delete Contract " ) 
    }

    def getContracts ( token , props , profileDetails , apiId  )
    
    {
    
        log ( INFO , " start Get Contracts " ) 
        
       
        def urlString = "https://anypoint.mulesoft.com/apimanager/api/v1/organizations/"+profileDetails.orgId+"/environments/"+profileDetails.envId + "/apis/"+apiId+"/contracts"
        def headers=["Content-Type":"application/json", "Authorization": "Bearer " + token, "Accept":"application/json" ]
        def connection = doRESTHTTPCall(urlString, "GET", null, headers)
        def response = null
        def contracts
        
        if ( connection.responseCode =~ '2..') 
        {
            response = "${connection.content}" 
            contracts = new JsonSlurper().parseText(response)
            log(INFO, " GetContracts is successful. statusCode=${connection.responseCode} responseMessage=${contracts}")
            
        }
        else {
        
            throw new Exception("Failed to get the contracts ! statusCode=${connection.responseCode} responseMessage=${contracts}")
        }
        
        log ( INFO , " end Get Contracts " ) 
        
        return contracts
    }
 
     def deleteApi ( token , profileDetails , apiId )
    
    {
    
        log ( INFO , " start Delete API " ) 
       
        def urlString = "https://anypoint.mulesoft.com/apimanager/api/v1/organizations/"+profileDetails.orgId+"/environments/"+profileDetails.envId + "/apis/"+apiId
        def headers=["Content-Type":"application/json", "Authorization": "Bearer " + token ]
        def connection = doRESTHTTPCall(urlString, "DELETE", null, headers)
        
        
        if ( connection.responseCode =~ '2..') 
        {
          log(INFO, " API Deleted successfully. statusCode=${connection.responseCode}")
            
        }
        else {
        
            throw new Exception("Failed to delete the API : ${apiId} ! statusCode=${connection.responseCode} ")
        }
        
        log ( INFO , " end Delete API " ) 
    }

    static def doRESTHTTPCall(urlString, method, payload, headers)
    {
        log(DEBUG,  "START doRESTHTTPCall")

        log(INFO, "requestURl is " + urlString)

        def url = new URL(urlString)

        def connection = url.openConnection()
        
        headers.keySet().each {
            log(INFO, it + "->" + headers.get(it))
            connection.setRequestProperty(it, headers.get(it))
        }
       

        connection.doOutput = true

        if (method == "POST")
        {
            connection.setRequestMethod("POST")
            def writer = new OutputStreamWriter(connection.outputStream)
            writer.write(payload)
            writer.flush()
            writer.close()
        }
        else if (method == "GET")
        {
            connection.setRequestMethod("GET")
        }
        else if (method == "PATCH")
        {
            connection.setRequestMethod("PATCH")
            def writer = new OutputStreamWriter(connection.outputStream)
            writer.write(payload)
            writer.flush()
            writer.close()
        }
        else if (method == "DELETE")
        {
            connection.setRequestMethod("DELETE")
        }
        
        connection.connect();
        

        log(DEBUG,  "END doRESTHTTPCall")

        return connection

    }
    


    def persisteAPIDiscoveryDetail (props, result)
    {
       
        
        Properties props1 = new Properties()
        def config = props.path+"/src/main/resources/config-"+props.envId+".properties"
        log(DEBUG,  "Config.properties path" + config )
        File propsFile = new File(config)
        props1.load(propsFile.newDataInputStream())
        log(DEBUG,  "Existing api.id=" + props1.getProperty('api.id') )
        props1.setProperty('api.id',result.apiDiscoveryId.toString())
        log(DEBUG,  "After change api.id=" + props1.getProperty('api.id') )
        props1.store(propsFile.newWriter(), null)

    }

    static void main(String[] args) {


        CICDUtil util = new CICDUtil();

        def props = util.init();
      
          
          def result = util.provisionAPIManager(props);
         
      
        util.persisteAPIDiscoveryDetail(props, result)
          
         

       } 
}