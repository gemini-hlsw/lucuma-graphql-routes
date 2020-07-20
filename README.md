# lucuma-odb-api

## Overview

This project aims to define an API for working with the Gemini Science Proposal
/ Program model.  It provides a service that stores programs in memory. It is 
initialized with an example program but all updates are lost when the service is
restarted.  The idea is to focus on the API rather than service implementation
details.  It is expected that the API will evolve rapidly at the outset as
features are worked out. Eventually we will throw out this service but be left
with the Schema SDL that it produces.  In the meantime, client implementations
can be developed against the API as it evolves and the science staff can
experiment with the API as well.

* TODO: publish

## How to experiment with the service in development

Start from sbt:

```
service/reStart
```

stop with:

```
service/reStop
```

Then browse to `http://localhost:8080/odb` to use the GraphQL Playground.  The
Playground UI contains generated schema documentation.

## Organization

There are two modules:

* `core` - contains the model and schema, or the meat of the project 
* `service` - contains http4s service for running the application

Within `core` there are three layers, from bottom up:

* `model` - Simple classes defining the types that appear in the model, without
any Sangria or GraphQL-specific code
* `repo` - Fake in-memory storage of programs
* `schema` - GraphQL schema using Sangria

## Current Status

A target schema that closely tracks the GPP project's target model is partially
completed.  There is a placeholder program that serves as a container for
targets.  Additionally there are placeholder asterisms and observations. An
example query for all sidereal targets in a program:

```
query Program {
  program(id: "p-2") {
    id
    name
    targets(includeDeleted: true) {
      id
      name
      tracking {
        __typename 
        ... on Sidereal {
          coordinates {
            ra { hms }
            dec { dms }
          }
          epoch
          properVelocity {
            ra
            dec
          }
        }
      }
    }
  }
}
```

Nonsidereal targets are supported as well, and the target tracking in that case
changes to contain the corresponding Horizons id:

```
tracking {
  __typename
  ... on Nonsidereal {
    keyType
    des
  }
}
```

A few simple mutations are available for top level types (`Asterism`,
`Observation`, `Program`, and `Target` so far).  For
example, sidereal target insertion:

```
mutation AddSiderealTarget($input: CreateSiderealInput!) {
  createSiderealTarget(input: $input) {
    id
    name
    tracking {
      __typename
      ... on Sidereal {
        coordinates {
          ra { hms }
          dec { dms }
        }
        epoch
        properVelocity {
          ra
          dec
        }
      }
    }
  }
  
}
{
  "input": {
    "pids": ["p-2"],
    "name": "Zero",
    "ra": "00:00:00.000",
    "dec": "00:00:00.000",
    "epoch": "J2000.000",
    "properVelocity": {
      "ra": "1.00",
      "dec": "2.00"
    }
  }
}
```

Sidereal target editing:

```
mutation UpdateSiderealTarget($input2: EditSiderealInput!) {
  updateSiderealTarget(input: $input2) {
    id
    name
    tracking {
      __typename
      ... on Sidereal {
        coordinates {
          ra { hms }
          dec { dms }
        }
      }
      
    }
  }
}


{
 "input2": {
    "id": "t-2",
    "name": "Zero",
    "ra": "00:00:00.000",
    "dec": "00:00:00.00"
  }
}
```

## Subscriptions

As a proof-of-concept, basic (WebSocket) subscriptions are supported for target updates.


```
subscription TargetUpdateSubscription {
  targetEdited {
    id
    oldValue {
      name
    }
    newValue {
      name
    }
  }
}
```

You can experiment via the command line with `websocat`.  For example:

```bash
$ websocat -t ws://localhost:8080/ws
{"type":"connection_init","payload":{}}
```

The server will respond with

__`{   "type" : "connection_ack" }`__


Start a subscription that will tell you the new name of any targets that are edited:

```
{"id":"1","type":"start","payload":{"variables":{},"extensions":{},"operationName":"Sub","query":"subscription Sub {\n  targetEdited {\n    id\n oldValue {\n      name\n    }\n    newValue {\n      name\n    }\n  }\n}\n"}}
```

If a target is renamed from "Rigel" to "Zero", the following event would be sent.

__`{   "type" : "data",   "id" : "1",   "payload" : {     "data" : {       "data" : {         "targetEdited" : {           "id": "t-2",          "oldValue" : {             "name" : "Rigel"           },           "newValue" : {             "name" : "Zero"           }         }       }     }   } }`__


Terminate the subscription with:

```
{"type":"stop", "id" : "1" }
```


The server acknowledges with a corresponding "complete":

__`{   "type" : "complete",   "id" : "1" }`__


Let the server know you are done:

```
{"type": "connection_terminate"}
```

