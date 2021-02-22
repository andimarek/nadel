# Nadel: A distributed GraphQL engine

Nadel is a Java library to combine several graphql services together.
This is achieved by combining several underlying GraphQL services (schemas) into one schema (overall schema).

The main purpose of this library is to power a GraphQL gateway which serves as the entry point for executing requests across different
services while presenting one GraphQL API.   

![Nadel GraphQL Gateway](docs/nadel-graphql-gateway.png)


This is achieved by declaring the services, which should be combined, in a DSL which 
is build on top of the GraphQL SDL (Schema Definition Language). 

While this library is in Java, the underlying services only need to provide a standard
GraphQL API and no Java Code is required to combine services together. 
It is all done declarative as part of the DSL.


## Example:

Lets assume we have two Services: "Issues" and "Users". One has Issues and one has Users. An Issue has a reference to its authors.

This is the schema for the Issues service:
```graphql
type Query {
    issues: [Issue]
}
type Issue {
    id: ID
    authorIds: [ID]
}
```

And this is the Users service:
```graphql
type Query {
    users(ids: [ID]): [User]
}
type User {
    id: ID
    fullName: String
}
```
This is how you would combine these two services together with Nadel while also renaming a field at the same time.

```graphql
service Issues {
    type Query {
        issues: [Issue]
    }
    type Issue {
        id: ID
        authors: [User] => hydrated from Users.users(ids: $source.authorIds) object identified by id, batch size 10
    }
}

service Users {
    type Query {
        users(ids: [ID]): [User]
    }
    type User {
        id: ID
        name: String => renamed from fullName
    }
}
```

The result is a new GraphQL API which combines the two services in one and has the following schema:

```graphql
type Query {
    issues: [Issue]
    users(ids: [ID]): [User]
}       
type Issue {
    id: ID
    authors: [User]
}
type User {
    id: ID
    name: String
}
``` 

## Features

- Explicit overall schema design: every field for the overall schema needs to be declared in the Nadel DSL. If it is not declared it will not be exposed
- Hydration: resolving referencing to other objects by calling another service
- Field rename: renaming field from the underlying schema to a new name in the overall schema
- Type rename: renaming a type from the underlying schema to a new name in the overall schema
- Batching: Hydration calls are automatically batched with a configurable max batch size per call  


## Getting started

