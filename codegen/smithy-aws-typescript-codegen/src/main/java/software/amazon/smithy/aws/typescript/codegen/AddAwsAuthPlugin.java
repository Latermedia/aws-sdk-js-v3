/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.aws.typescript.codegen;

import static software.amazon.smithy.aws.typescript.codegen.AwsTraitsUtils.isSigV4Service;
import static software.amazon.smithy.typescript.codegen.integration.RuntimeClientPlugin.Convention.HAS_CONFIG;
import static software.amazon.smithy.typescript.codegen.integration.RuntimeClientPlugin.Convention.HAS_MIDDLEWARE;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import software.amazon.smithy.aws.traits.ServiceTrait;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.traits.OptionalAuthTrait;
import software.amazon.smithy.typescript.codegen.LanguageTarget;
import software.amazon.smithy.typescript.codegen.TypeScriptDependency;
import software.amazon.smithy.typescript.codegen.TypeScriptSettings;
import software.amazon.smithy.typescript.codegen.TypeScriptWriter;
import software.amazon.smithy.typescript.codegen.integration.RuntimeClientPlugin;
import software.amazon.smithy.typescript.codegen.integration.TypeScriptIntegration;
import software.amazon.smithy.utils.IoUtils;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SetUtils;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Configure clients with AWS auth configurations and plugin.
 */
// TODO: Think about AWS Auth supported for only some operations and not all, when not AWS service, with say @auth([])
@SmithyInternalApi
public final class AddAwsAuthPlugin implements TypeScriptIntegration {
    static final String STS_CLIENT_PREFIX = "sts-client-";
    static final String ROLE_ASSUMERS_FILE = "defaultRoleAssumers";
    static final String STS_ROLE_ASSUMERS_FILE = "defaultStsRoleAssumers";

    @Override
    public void addConfigInterfaceFields(
        TypeScriptSettings settings,
        Model model,
        SymbolProvider symbolProvider,
        TypeScriptWriter writer
    ) {
        ServiceShape service = settings.getService(model);
        if (!isSigV4Service(service)) {
            return;
        }
        if (!areAllOptionalAuthOperations(model, service)) {
            writer.addImport("Credentials", "__Credentials", TypeScriptDependency.AWS_SDK_TYPES.packageName);
            writer.writeDocs("Default credentials provider; Not available in browser runtime.")
                .write("credentialDefaultProvider?: (input: any) => __Provider<__Credentials>;\n");
        }
    }

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        return ListUtils.of(
            RuntimeClientPlugin.builder()
                    .withConventions(AwsDependency.MIDDLEWARE_SIGNING.dependency, "AwsAuth", HAS_CONFIG)
                    .servicePredicate((m, s) -> isSigV4Service(s)
                            && !areAllOptionalAuthOperations(m, s)
                            && !testServiceId(s, "STS"))
                    .build(),
            RuntimeClientPlugin.builder()
                    .withConventions(AwsDependency.STS_MIDDLEWARE.dependency,
                            "StsAuth", HAS_CONFIG)
                    .additionalResolveFunctionParameters("STSClient")
                    .servicePredicate((m, s) -> testServiceId(s, "STS"))
                    .build(),
            RuntimeClientPlugin.builder()
                    .withConventions(AwsDependency.MIDDLEWARE_SIGNING.dependency, "AwsAuth", HAS_MIDDLEWARE)
                    // See operationUsesAwsAuth() below for AwsAuth Middleware customizations.
                    .servicePredicate(
                        (m, s) -> !testServiceId(s, "STS") && isSigV4Service(s) && !hasOptionalAuthOperation(m, s)
                    ).build(),
            RuntimeClientPlugin.builder()
                    .withConventions(AwsDependency.MIDDLEWARE_SIGNING.dependency, "AwsAuth", HAS_MIDDLEWARE)
                    .operationPredicate(AddAwsAuthPlugin::operationUsesAwsAuth)
                    .build()
        );
    }

    @Override
    public Map<String, Consumer<TypeScriptWriter>> getRuntimeConfigWriters(
        TypeScriptSettings settings,
        Model model,
        SymbolProvider symbolProvider,
        LanguageTarget target
    ) {
        ServiceShape service = settings.getService(model);
        if (!isSigV4Service(service) || areAllOptionalAuthOperations(model, service)) {
            return Collections.emptyMap();
        }
        switch (target) {
            case BROWSER:
                return MapUtils.of(
                    "credentialDefaultProvider", writer -> {
                        writer.write(
                                "credentialDefaultProvider: (_: unknown) => () => Promise.reject(new Error("
                                    + "\"Credential is missing\")),");
                    }
                );
            case NODE:
                return MapUtils.of(
                    "credentialDefaultProvider", writer -> {
                        if (!testServiceId(service, "STS")) {
                            writer.addDependency(AwsDependency.STS_CLIENT);
                            writer.addImport("decorateDefaultCredentialProvider", "decorateDefaultCredentialProvider",
                                    AwsDependency.STS_CLIENT.packageName);
                        } else {
                            writer.addImport("decorateDefaultCredentialProvider", "decorateDefaultCredentialProvider",
                                    "./" + STS_ROLE_ASSUMERS_FILE);
                        }
                        writer.addDependency(AwsDependency.CREDENTIAL_PROVIDER_NODE);
                        writer.addImport("defaultProvider", "credentialDefaultProvider",
                                AwsDependency.CREDENTIAL_PROVIDER_NODE.packageName);
                        writer.write("credentialDefaultProvider: decorateDefaultCredentialProvider("
                                + "credentialDefaultProvider),");
                    }
                );
            default:
                return Collections.emptyMap();
        }
    }

    @Override
    public void writeAdditionalFiles(
        TypeScriptSettings settings,
        Model model,
        SymbolProvider symbolProvider,
        BiConsumer<String, Consumer<TypeScriptWriter>> writerFactory
    ) {
        ServiceShape service = settings.getService(model);
        if (!testServiceId(service, "STS")) {
            return;
        }
        writerFactory.accept("defaultRoleAssumers.ts", writer -> {
            String source = IoUtils.readUtf8Resource(getClass(),
                    String.format("%s%s.ts", STS_CLIENT_PREFIX, ROLE_ASSUMERS_FILE));
            writer.write("$L", source);
        });
        writerFactory.accept("defaultStsRoleAssumers.ts", writer -> {
            String source = IoUtils.readUtf8Resource(getClass(),
                    String.format("%s%s.ts", STS_CLIENT_PREFIX, STS_ROLE_ASSUMERS_FILE));
            writer.write("$L", source);
        });
    }

    @Override
    public void writeAdditionalExports(
        TypeScriptSettings settings,
        Model model,
        SymbolProvider symbolProvider,
        TypeScriptWriter writer
    ) {
        ServiceShape service = settings.getService(model);
        if (!testServiceId(service, "STS")) {
            return;
        }
        writer.write("export * from $S", "./" + ROLE_ASSUMERS_FILE);
    }

    private static boolean testServiceId(Shape serviceShape, String expectedId) {
        return serviceShape.getTrait(ServiceTrait.class).map(ServiceTrait::getSdkId).orElse("").equals(expectedId);
    }

    private static boolean operationUsesAwsAuth(Model model, ServiceShape service, OperationShape operation) {
        // STS doesn't need auth for AssumeRoleWithWebIdentity, AssumeRoleWithSAML.
        // Remove when optionalAuth model update is published in 0533102932.
        if (testServiceId(service, "STS")) {
            Boolean isUnsignedCommand = SetUtils
                    .of("AssumeRoleWithWebIdentity", "AssumeRoleWithSAML")
                    .contains(operation.getId().getName(service));
            return !isUnsignedCommand;
        }

        // optionalAuth trait doesn't require authentication.
        if (isSigV4Service(service) && hasOptionalAuthOperation(model, service)) {
            return !operation.hasTrait(OptionalAuthTrait.class);
        }
        return false;
    }

    private static boolean hasOptionalAuthOperation(Model model, ServiceShape service) {
        TopDownIndex topDownIndex = TopDownIndex.of(model);
        Set<OperationShape> operations = topDownIndex.getContainedOperations(service);
        for (OperationShape operation : operations) {
            if (operation.hasTrait(OptionalAuthTrait.class)) {
                return true;
            }
        }
        return false;
    }

    private static boolean areAllOptionalAuthOperations(Model model, ServiceShape service) {
        TopDownIndex topDownIndex = TopDownIndex.of(model);
        Set<OperationShape> operations = topDownIndex.getContainedOperations(service);
        for (OperationShape operation : operations) {
            if (!operation.hasTrait(OptionalAuthTrait.class)) {
                return false;
            }
        }
        return true;
    }
}
