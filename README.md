# vertx-exploration
Exploration of Vert.x framework and reactive programming in general

This is a simple wiki app based on https://vertx.io/preview/docs/guide-for-java-devs/ (followed until "Reactive programming with RxJava" section)

To run it, 

1. run mvn clean install 
2. in target folder, run java -jar webapp-1.0.0-SNAPSHOT-fat.jar
3. access it via https://localhost:8080 (users are listed in src/main/resources/wiki-users.properties)

To debug in Eclipse,

1. right click on the project -> debug as -> Debug configuration 
2. on the left menu, select "Java Application", right click -> new
3. in Project: select the current project
4. in Main Class: io.vertx.core.Launcher
5. In Arguments -> Program arguments: run com.vertxexploration.webapp.MainVerticle
6. in Arguments -> VM arguments: -Dhsqldb.reconfig_logging=false
7. Click Debug and the server will be initialized. 
8. Now you can put the breakpoint in the code and start debugging
