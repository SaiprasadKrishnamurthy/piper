# Introduction

Piper is a distributed workflow engine designed to be dead simple. 

Piper can run on one or a thousand machines depending on your scaling needs. 

In Piper, work to be done is defined as a set of tasks called a Pipeline. Pipelines can be sourced from many locations but typically they live on a Git repository where they can be versioned and tracked.

Piper was originally built to support the need to transcode massive amounts of video in parallel. Since transcoding video is a CPU and time instensive process I had to scale horizontally. Moreover, I needed a way to monitor these long running jobs, auto-retry them and otherwise control their execution. 

# Tasks

Tasks are the basic building blocks of a pipeline. Each task has a `type` property which maps to a `TaskHandler` implementation, responsible for carrying out the task.

For example here's the `RandomInt` `TaskHandler` implementation:

```
  public class RandomInt implements TaskHandler<Object> {

    @Override
    public Object handle(Task aTask) throws Exception {
      int startInclusive = aTask.getInteger("startInclusive", 0);
      int endInclusive = aTask.getInteger("endInclusive", 100);
      return RandomUtils.nextInt(startInclusive, endInclusive);
    }
    
  }
```

While it doesn't do much beyond generating a random integer, it does  demonstrate how a `TaskHandler` works. a `Task` instance is passed as  an argument to 
the `TaskHandler` which contains all the Key-Value pairs of that task.

The `TaskHandler` is then responsible for executing the task using this input and optionally returning an output which can be used by other pipeline tasks downstream.


# Pipelines

Piper pipelines are authored in YAML, a JSON superset. 

Here is an example of a basic pipeline definition.

```
name: Hello Demo

inputs:                --+
  - name: yourName       |
    label: Your Name     | - This defines the inputs
    type: string         |   expected by the pipeline
    required: true     --+

tasks: 
  - name: randomNumber               --+
    label: Generate a random number    |
    type: randomInt                    | - This is a task
    startInclusive: 0                  |
    endInclusive: 10000              --+
                            
  - label: Print a greeting 
    type: print             
    text: Hello ${yourName} 
                           
  - label: Sleep a little
    type: sleep             --+
    millis: ${randomNumber}   | - tasks may refer to the result of a previous task
                            --+
  - label: Print a farewell
    type: print
    text: Goodbye ${yourName}
```


So tasks are nothing but a collection of key-value pairs. At a minimum each task contains a `type` property which maps to an appropriate `TaskHandler` that needs to execute it.

Tasks may also specify a `name` property which can be used to name the output of the task so it can be used later in the pipeline.

The `label` property is used to give a human-readble description for the task.

The `node` property can be used to route tasks to work queues other than the default `tasks` queue. This allows one to design a cluster of worker nodes of different types, of different capacity, different 3rd party software dependencies and so on.

The `retry` property can be used to specify the number of times that a task is allowed to automatically retry in case of a failure.

The `timeout` property can be used to specify the number of seconds/minutes/hours that a task may execute before it is cancelled.

The `output` property can be used to modify the output of the task in some fashion. e.g. convert it to an integer.

All other key-value pairs are task-specific and may or may not be required depending on the specific task.


# Architecture

Piper is composed of the following components: 

**Coordinator**: The Coordinator is the like the central nervous system of Piper. It keeps tracks of jobs, dishes out work to be done by Worker machines, keeps track of failures, retries and other job-level details. Unlike Worker nodes, it does not execute actual work but delegate all task activities to Worker instances. 

**Worker**: Workers are the work horses of Piper. These are the Piper nodes that actually execute tasks requested to be done by the Coordinator machine. Unlike the Coordinator, the workers are stateless, which by that is meant that they do not interact with a database or keep any state in memory about the job or anything else. This makes it very easy to scale up and down the number of workers in the system without fear of losing application state. 

**Message Broker**:  All communication between the Coordinator and the Worker nodes is done through a messaging broker. This has many advantages: 
  1. if all workers are busy the message broker will simply queue the message until they can handle it. 
  2. when workers boot up they subscribe to the appropriate queues for the type of work they are intended to handle 
  3. if a worker crashes the task will automatically get re-queued to be handle by another worker.
  4. Last but not least, workers and `TaskHandler` implementations can be written in any language since they decoupled completely through message passing.  

**Database**: This piece holds all the jobs state in the system, what tasks completed, failed etc. It is used by the Coordinator as its "mind". 

**Pipeline Repository**: The component where pipelines (workflows) are created, edited etc. by pipeline engineers.

# Control Flow

Piper support the following constructs to control the flow of execution:

## Each

Applies the function `iteratee` to each item in `list`, in parallel. Note, that since this function applies iteratee to each item in parallel, there is no guarantee that the `iteratee` functions will complete in order.


```
- type: each
  list: [1000,2000,3000]
  iteratee:
    type: sleep         
    millis: ${item} 
```

This will generate three parallel tasks, one for each items in the list, which will `sleep` for 1, 2 and 3 seconds respectively.

## Parallel

Run the `tasks` collection of functions in parallel, without waiting until the previous function has completed.

```
- type: parallel
  tasks: 
    - type: print
      millis: hello
        
    - type: print
      text: goodbye
```

## Fork/Join

Executes each branch in the `branches` as a seperate and isolated sub-flow. Branches are executed internally in sequence.

```
- type: fork
  branches: 
     - - name: randomNumber                 <-- branch 1 start here
         label: Generate a random number
         type: randomInt
         startInclusive: 0
         endInclusive: 5000
           
       - type: sleep
         millis: ${randomNumber}
           
     - - name: randomNumber                 <-- branch 2 start here
         label: Generate a random number
         type: randomInt
         startInclusive: 0
         endInclusive: 5000
           
       - type: sleep
         millis: ${randomNumber}      
```

## Switch

Executes one and only one branch of execution based on the `expression` value.

```
- type: switch
  expression: ${selector} <-- determines which case will be executed
  cases: 
     - key: hello                 <-- case 1 start here
       tasks: 
         - type: print
           text: hello world
     - key: bye                   <-- case 2 start here
       tasks: 
         - type: print
           text: goodbye world
  default:
    - tasks:
        -type: print
         text: something else
```

## Map

Produces a new collection of values by mapping each value in `list` through the `iteratee` function. The `iteratee` is called with an item from `list` in parallel. When the `iteratee` is finished executing on all items the `map` task will return a list of execution results in an order which corresponds to the order of the source `list`.

```
- name: fileSizes 
  type: map
  list: ["/path/to/file1.txt","/path/to/file2.txt","/path/to/file3.txt"]
  iteratee:
    type: filesize         
    file: ${item}
``` 

# Tutorials

## Hello World

Build Piper:

```
./scripts/build.sh
```

Start Piper in memory without any external dependencies. Great for hassle-free development:

```
./scripts/development.sh
```

Go to the browser at <a href="http://localhost:8080/jobs" target="_blank">http://localhost:8080/jobs</a>

Which should give you something like:

```
{
  number: 0,
  totalItems: 0,
  size: 0,
  totalPages: 0,
  items: [ ]
}
```

The `/jobs` endpoint lists all jobs that are either running or were previously run on Piper.

Start a demo job:

```
curl -s -X POST -H Content-Type:application/json -d '{"pipelineId":"demo/hello","inputs":{"yourName":"Joe Jones"}}' http://localhost:8080/jobs
```

Which should give you something like this as a response: 

```
{
  "createTime": "2017-07-05T16:56:27.402+0000",
  "webhooks": [],
  "inputs": {
    "yourName": "Joe Jones"
  },
  "id": "8221553af238431ab006cc178eb59129",
  "label": "Hello Demo",
  "priority": 0,
  "pipelineId": "demo/hello",
  "status": "CREATED",
  "tags": []
}
```


If you'll refresh your browser page now you should see the executing job. 

In case you are wondering, the `demo/hello` pipeline is located at <a href="https://github.com/creactiviti/piper/blob/master/piper-core/src/main/resources/pipelines/demo/hello.yaml" target="_blank">here</a>


## Writing your first pipeline

Create the directory `~/piper/pipelines` and create a file in there called `mypipeline.yaml`.

Edit the file and the following text:

```
label: My Pipeline

inputs:
  - name: name
    type: string
    required: true

tasks:      
  - label: Print a greeting
    type: print
    text: Hello ${name}
       
  - label: Print a farewell
    type: print
    text: Goodbye ${name}
    
```  

Execute your workflow

```
curl -s -X POST -H Content-Type:application/json -d '{"pipelineId":"mypipeline","inputs":{"name":"Arik"}}' http://localhost:8080/jobs
```

You can make changes to your pipeline and execute the `./scripts/clear.sh` to clear the cache to reload the pipeline.

## Scaling Piper

Depending on your workload you will probably exhaust the ability to run Piper on a single node fairly quickly. Good, because that's where the fun begins. 

Start RabbitMQ: 

```
./scripts/rabbit.sh
```

Start the Coordinator: 

```
./scripts/coordinator.sh 
```

From another terminal window, start a Worker:

```
./scripts/worker.sh 
```

Execute the demo pipeline: 

```
curl -s -X POST -H Content-Type:application/json -d '{"pipelineId":"demo/hello","inputs":{"yourName":"Joe Jones"}}' http://localhost:8080/jobs
```


## Transcoding a Video

Note: You must have [ffmpeg](https://ffmpeg.org) installed on your worker machine to get this demo to work


Transcode a source video to an SD (480p) output:

```
curl -s -X POST -H Content-Type:application/json -d '{"pipelineId":"video/transcode","inputs":{"input":"/path/to/video/input.mov","output":"/path/to/video/output.mp4","profile":"sd"}}' http://localhost:8080/jobs
```

Transcode a source video to an HD (1080p) output:

```
curl -s -X POST -H Content-Type:application/json -d '{"pipelineId":"video/transcode","inputs":{"input":"/path/to/video/input.mov","output":"/path/to/video/output.mp4","profile":"hd"}}' http://localhost:8080/jobs
```

## Transcoding a Video (Split & Stitch)

This tutorial demostrates how to transcode a source video by splitting it to chunks and transcoding these chunks in parallel, potentially by multiple nodes.

Note: You must have [ffmpeg](https://hub.docker.com/r/jrottenberg/ffmpeg/) installed on your worker machine to get this demo to work


```
curl -s -X POST -H Content-Type:application/json -d '{"pipelineId":"video/split_n_stitch","inputs":{"input":"/path/to/input.mov","output":"/path/to/output.mp4"}}' http://localhost:8080/jobs 
```


# Using Git as a Pipeline Repository backend

Rather than storing the pipelines in your local file system you can use Git to store them for you. This has great advantages, not the least of which is pipeline versioning, Pull Requests and everything else Git has to offer.

To enable Git as a pipeline repository set the `piper.pipeline-repository.git.enabled` flag to `true` in `./scripts/development.sh` and restart Piper. By default, Piper will use the demo repository [piper-pipelines](https://github.com/creactiviti/piper-pipelines).

You can change it by using the `piper.pipeline-repository.git.url` and `piper.pipeline-repository.git.search-paths` configuration parameters.  

# Configuration

```
# messaging provider between Coordinator and Workers (jms | amqp) default: jms
piper.messenger.provider=jms
# turn on the Coordinator process
piper.coordinator.enabled=true
# turn on the Worker process and listen to tasks.
piper.worker.enabled=true
# when worker is enabled, subscribe to the default "tasks" queue with 5 concurrent consumers. 
# you may also route pipeline tasks to other arbitrarilty named task queues by specifying the "node"
# property on any give task. 
# E.g. node: captions will route to the captions queue which a worker would subscribe to with piper.worker.subscriptions.captions
# note: queue must be created before tasks can be routed to it. Piper will create the queue if it isn't already there when the worker
# bootstraps.
piper.worker.subscriptions.tasks=5 
# enable a git-based pipeline repository
piper.pipeline-repository.git.enabled=true
# The URL to the Git Repo
piper.pipeline-repository.git.url=git@github.com:creactiviti/piper-pipelines.git
# folders within the git repo that are scanned for pipelines.
piper.pipeline-repository.git.search-paths=demo/,video/
# enable file system based pipeline repository
piper.pipeline-repository.filesystem.enabled=true
# location of pipelines on the file system.
piper.pipeline-repository.filesystem.location-pattern=$HOME/piper/**/*.yaml
# data source
spring.datasource.initialize=true # Create the database using 'schema-{platform}.sql'.
spring.datasource.name= # Name of the datasource.
spring.datasource.password= # Login password of the database.
spring.datasource.platform=h2 # Platform to use in the DDL or DML scripts. Supports h2 and postgres.
spring.datasource.url= # JDBC url of the database.
spring.datasource.username= # Login user of the database.
```

# Docker

[creactiviti/piper](https://hub.docker.com/r/creactiviti/piper)

Hello World in Docker:

Create an empty directory: 

```
mkdir pipelines
cd pipelines
```

Create a simple pipeline file --  `hello.yaml` -- and paste the following to it: 

```
label: Hello World
    
inputs:
  - name: name
    label: Your Name
    type: string
    required: true
    
tasks:      
  - label: Print Hello Message
    type: print
    text: "Hello ${name}!"
```

```
docker run --name=piper --rm -it -e piper.worker.enabled=true -e piper.coordinator.enabled=true -e piper.worker.subscriptions.tasks=1 -e piper.pipeline-repository.filesystem.enabled=true -e piper.pipeline-repository.filesystem.location-pattern=/pipelines/**/*.yaml -v $PWD:/pipelines -p 8080:8080 creactiviti/piper
```

```
curl -s -X POST -H Content-Type:application/json -d '{"pipelineId":"hello","inputs":{"name":"Joe Jones"}}' http://localhost:8080/jobs
```


# License
Piper is released under version 2.0 of the [Apache License][]. 

## Support
If you need professional support feel free to contact [me](mailto:arik@creactiviti.com) for details.

[Apache License]: http://www.apache.org/licenses/LICENSE-2.0
