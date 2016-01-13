we have this fork because the modification for resolving only on the local machine was not accepted by Apache :
Once the build is done it is deploy on Talend Nexus using this command
mvn deploy:deploy-file -Dfile=/Users/sgandon/Talend/git/org.ops4j.pax.url/pax-url-aether/target/pax-url-aether-2.4.5.jar -DpomFile=pom.xml -Durl=http://newbuild.talend.com:8081/nexus/content/repositories/TalendOpenSourceRelease/ -DrepositoryId=talend_nexus_deployment

