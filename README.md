# nexus-localservice-plugin

## Introduction
This plugin is indented for Nexus 3 and provide the two followings missing api from Nexus 2:
- [/artifact/maven/resolve](https://repository.sonatype.org/nexus-restlet1x-plugin/default/docs/path__artifact_maven_resolve.html)
- [/artifact/maven/content](https://repository.sonatype.org/nexus-restlet1x-plugin/default/docs/path__artifact_maven_content.html)

It is aimed at facilitating migration to Nexus 3 if you have a lot of scripts ( Jenkins groovy in jobs, sh, Ansible) that use those two api. You can after the migration To Nexus3 begin working on your script to use normal apis from Nexus3.

Functionality is the same as in Nexus 2 : based on  release and snahots versions infos in te mavan metadata (with all the caveat ...)

http://localhost:8081/service/rest/servicelocal/artifact/maven/resolve
Methode: GET
Params:
- g 	Group id of the artifact (Required). 	query 	
- a 	Artifact id of the artifact (Required). 	query 	
- v 	Version of the artifact (Required) Supports resolving of "LATEST", "RELEASE" and snapshot versions ("1.0-SNAPSHOT") too. 	query 	
- r 	Repository that the artifact is contained in (Required). 	query 	
- p 	Packaging type of the artifact (Optional). 	query 	
- c 	Classifier of the artifact (Optional). 	query 	
- e 	Extension of the artifact (Optional). 

Response:
element: 	artifact-resolution
media types: 
- application/xml
- application/json

http://localhost:8081/service/rest/servicelocal/artifact/maven/content
Methode: GET
Params:
- g 	Group id of the artifact (Required). 	query 	
- a 	Artifact id of the artifact (Required). 	query 	
- v 	Version of the artifact (Required) Supports resolving of "LATEST", "RELEASE" and snapshot versions ("1.0-SNAPSHOT") too. 	query 	
- r 	Repository that the artifact is contained in (Required). 	query 	
- p 	Packaging type of the artifact (Optional). 	query 	
- c 	Classifier of the artifact (Optional). 	query 	
- e 	Extension of the artifact (Optional). 

Response:
- binary file attachment

## Installation
- compile the project 

if the jar is not published in a maven repository
- copy nexus-localservice-plugin-$VERSION$.jar to $NEXUS_HOME\system\com\ptoceti\nexus3\plugin\localservice\nexus-localservice-plugin\$PLUGINVERSION
- copy ./target/feature/feature.xml (take inside tag 'feature' and content) in NEXUS_HOME\system\com\sonatype\nexus\assemblies\nexus-oss-feature\NEXUS_VERSION\nexus-oss-feature-3.xx-features, at the end of the list of features.
- modify in the same file (nexus-oss-feature-3.xx-features) the feature <feature name="nexus-oss-feature"/> so that it include a ref to the plugin feature:
eg:
```
<feature version="1.0.0-SNAPSHOT" prerequisite="false" dependency="false">nexus-localservice-plugin</feature>
```

If the jar is publiahed in maven, you can use a maven loader in karaf:

- NEXUS_HOME/nexus/etc/karaf/profile.cfg append config bundle.mvn\:com.ptoceti.nexus3.plugin.localservice/nexus-localservice-plugin/${version} = mvn:com.ptoceti.nexus3.plugin.localservice/nexus-localservice-plugin/${version}

- NEXUS_HOME/nexus/etc/karaf/startup.properties append config reference\:file\:com.ptoceti.nexus3.plugin.localservice/${version}/nexus-localservice-plugin-${version}.jar = 200

## Use
To make the 2 api available on the same url as in Nexus2, modify NEXUS_HOME\etc\jetty\jetty.xml:
- add reqrite rule:
```
<New id="ServiceLocalRewrite" class="org.eclipse.jetty.rewrite.handler.RewriteHandler">
      <Call name="addRule">
        <Arg>
          <New class="org.eclipse.jetty.rewrite.handler.RewriteRegexRule">
            <Set name="regex">/service/local/artifact/maven/(.*)</Set>
            <Set name="replacement">/service/rest/servicelocal/artifact/maven/$1</Set>
          </New>
        </Arg>
      </Call>
  </New>
 ```
 - add ref to rewrite rule in the existing list of handlers:
 ```
 <Set name="handler">
     <New id="Handlers" class="org.eclipse.jetty.server.handler.HandlerCollection">
       <Set name="handlers">
         <Array type="org.eclipse.jetty.server.Handler">
 			<Item>
 				<Ref refid="ServiceLocalRewrite"/>
 			  </Item>
           <Item>
             <Ref refid="NexusHandler"/>
           </Item>
         </Array>
       </Set>
     </New>
   </Set>
```