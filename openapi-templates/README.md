# OpenAPI templates

openapi-generator templates that make Java records, models only, which need nothing but `jackson-annotations` and
`jspecify` to compile, refuse null in a required field and keep NullAway green. They are copies of the generator's own
`JavaSpring` templates with changes, published under Apache-2.0, and the clients' records are generated with them.
They are not a client and contain no code that runs.

- A consumer adds `org.fuin.porelo:porelo-forge-client-openapi-templates` as a dependency of
  `openapi-generator-maven-plugin` and sets `templateResourcePath` to `org/fuin/porelo/forge/openapi/templates`,
  without a leading or trailing `/`. Without the artifact on the plugin's class path the generation fails, naming that
  path.
- They fit exactly the generator release `OpenApiTemplates.GENERATOR_VERSION` names; pin the plugin to it.
- They are the whole template set of an execution, not to be mixed with a `templateDirectory` of one's own.
- The generator's options are to be copied whole from [doc/records.md](../doc/records.md#the-generator): one left out
  writes code that needs Spring, or no `@NullMarked` `package-info.java`. Compile the generated package under
  NullAway, which refuses the shapes the templates do not cover, listed there too.
