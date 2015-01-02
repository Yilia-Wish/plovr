Plovr Latest Compiler Port
==================

plovr is a build tool that dynamically recompiles JavaScript and Closure Template code. It is designed to simplify Closure development, and to make it more enjoyable. see http://plovr.com/ for more info.


## Key Changes

+ Switched the build process to maven to support pushing to oss sonatype repo. 
+ Set 'experimental compiler option' for reporting on unknown types to always warn
+ Upgraded guava 17.0 to support mix and match between soy / core compiler
+ upgraded compiler to latest from github ( https://github.com/google/closure-compiler )
+ removed many of the packaged lib dependencies and repackaged as maven dependencies


## Further Work

Still need to remove the old build xml process, right now they run in parallel. It would also be preferred to package the relevant javascript resources for closure compiler with the maven dependency. Right now theres some additional folders hanging around for the resources, same goes for soy. 


