/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.android.aapt.MergeAndroidResourceSources;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Description for a {@link BuildRule} that generates an {@code .aar} file.
 * <p/>
 * This represents an Android Library Project packaged as an {@code .aar} bundle as specified by:
 * <a> http://tools.android.com/tech-docs/new-build-system/aar-format</a>.
 * <p/>
 * Note that the {@code aar} may be specified as a {@link SourcePath}, so it could be either
 * a binary {@code .aar} file checked into version control, or a zip file that conforms to the
 * {@code .aar} specification that is generated by another build rule.
 */
public class AndroidAarDescription implements Description<AndroidAarDescription.Arg> {

  private static final Flavor AAR_ANDROID_MANIFEST_FLAVOR =
      InternalFlavor.of("aar_android_manifest");
  private static final Flavor AAR_ASSEMBLE_RESOURCE_FLAVOR =
      InternalFlavor.of("aar_assemble_resource");
  private static final Flavor AAR_ASSEMBLE_ASSETS_FLAVOR =
      InternalFlavor.of("aar_assemble_assets");
  private static final Flavor AAR_ANDROID_RESOURCE_FLAVOR =
      InternalFlavor.of("aar_android_resource");

  private final AndroidManifestDescription androidManifestDescription;
  private final CxxBuckConfig cxxBuckConfig;
  private final JavacOptions javacOptions;
  private final ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> nativePlatforms;

  public AndroidAarDescription(
      AndroidManifestDescription androidManifestDescription,
      CxxBuckConfig cxxBuckConfig,
      JavacOptions javacOptions,
      ImmutableMap<NdkCxxPlatforms.TargetCpuType, NdkCxxPlatform> nativePlatforms) {
    this.androidManifestDescription = androidManifestDescription;
    this.cxxBuckConfig = cxxBuckConfig;
    this.javacOptions = javacOptions;
    this.nativePlatforms = nativePlatforms;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams originalBuildRuleParams,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {

    originalBuildRuleParams.getBuildTarget().checkUnflavored();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    ImmutableList.Builder<BuildRule> aarExtraDepsBuilder = ImmutableList.<BuildRule>builder()
        .addAll(originalBuildRuleParams.getExtraDeps().get());

    /* android_manifest */
    AndroidManifestDescription.Arg androidManifestArgs =
        androidManifestDescription.createUnpopulatedConstructorArg();
    androidManifestArgs.skeleton = args.manifestSkeleton;
    androidManifestArgs.deps = args.deps;

    BuildRuleParams androidManifestParams =
        originalBuildRuleParams.withAppendedFlavor(AAR_ANDROID_MANIFEST_FLAVOR);

    AndroidManifest manifest = androidManifestDescription.createBuildRule(
        targetGraph,
        androidManifestParams,
        resolver,
        androidManifestArgs);
    aarExtraDepsBuilder.add(resolver.addToIndex(manifest));

    final APKModuleGraph apkModuleGraph = new APKModuleGraph(
        targetGraph,
        originalBuildRuleParams.getBuildTarget(),
        Optional.empty());

    /* assemble dirs */
    AndroidPackageableCollector collector =
        new AndroidPackageableCollector(
            originalBuildRuleParams.getBuildTarget(),
            /* buildTargetsToExcludeFromDex */ ImmutableSet.of(),
            /* resourcesToExclude */ ImmutableSet.of(),
            apkModuleGraph);
    collector.addPackageables(
        AndroidPackageableCollector.getPackageableRules(
            originalBuildRuleParams.getDeps()));
    AndroidPackageableCollection packageableCollection = collector.build();

    ImmutableSortedSet<BuildRule> androidResourceDeclaredDeps =
        AndroidResourceHelper.androidResOnly(originalBuildRuleParams.getDeclaredDeps().get());
    ImmutableSortedSet<BuildRule> androidResourceExtraDeps =
        AndroidResourceHelper.androidResOnly(originalBuildRuleParams.getExtraDeps().get());

    BuildRuleParams assembleAssetsParams = originalBuildRuleParams
        .withAppendedFlavor(AAR_ASSEMBLE_ASSETS_FLAVOR)
        .copyReplacingDeclaredAndExtraDeps(
            Suppliers.ofInstance(androidResourceDeclaredDeps),
            Suppliers.ofInstance(androidResourceExtraDeps));
    ImmutableCollection<SourcePath> assetsDirectories =
        packageableCollection.getAssetsDirectories();
    AssembleDirectories assembleAssetsDirectories = new AssembleDirectories(
        assembleAssetsParams,
        assetsDirectories);
    aarExtraDepsBuilder.add(resolver.addToIndex(assembleAssetsDirectories));

    BuildRuleParams assembleResourceParams = originalBuildRuleParams
        .withAppendedFlavor(AAR_ASSEMBLE_RESOURCE_FLAVOR)
        .copyReplacingDeclaredAndExtraDeps(
            Suppliers.ofInstance(androidResourceDeclaredDeps),
            Suppliers.ofInstance(androidResourceExtraDeps));
    ImmutableCollection<SourcePath> resDirectories =
        packageableCollection.getResourceDetails().getResourceDirectories();
    MergeAndroidResourceSources assembleResourceDirectories = new MergeAndroidResourceSources(
        assembleResourceParams,
        resDirectories);
    aarExtraDepsBuilder.add(resolver.addToIndex(assembleResourceDirectories));

    /* android_resource */
    BuildRuleParams androidResourceParams = originalBuildRuleParams
        .withAppendedFlavor(AAR_ANDROID_RESOURCE_FLAVOR)
        .copyReplacingDeclaredAndExtraDeps(
            Suppliers.ofInstance(
                ImmutableSortedSet.of(
                    manifest,
                    assembleAssetsDirectories,
                    assembleResourceDirectories)),
            Suppliers.ofInstance(ImmutableSortedSet.of()));

    AndroidResource androidResource = new AndroidResource(
        androidResourceParams,
        ruleFinder,
        /* deps */ ImmutableSortedSet.<BuildRule>naturalOrder()
        .add(assembleAssetsDirectories)
        .add(assembleResourceDirectories)
        .addAll(originalBuildRuleParams.getDeclaredDeps().get())
        .build(),
        assembleResourceDirectories.getSourcePathToOutput(),
        /* resSrcs */ ImmutableSortedMap.of(),
        /* rDotJavaPackage */ null,
        assembleAssetsDirectories.getSourcePathToOutput(),
        /* assetsSrcs */ ImmutableSortedMap.of(),
        manifest.getSourcePathToOutput(),
        /* hasWhitelistedStrings */ false);
    aarExtraDepsBuilder.add(resolver.addToIndex(androidResource));

    ImmutableSortedSet.Builder<SourcePath> classpathToIncludeInAar =
        ImmutableSortedSet.naturalOrder();
    classpathToIncludeInAar.addAll(packageableCollection.getClasspathEntriesToDex());
    aarExtraDepsBuilder.addAll(BuildRules.toBuildRulesFor(
        originalBuildRuleParams.getBuildTarget(),
        resolver,
        packageableCollection.getJavaLibrariesToDex()));

    if (!args.buildConfigValues.getNameToField().isEmpty() && !args.includeBuildConfigClass) {
      throw new HumanReadableException("Rule %s has build_config_values set but does not set " +
          "include_build_config_class to True. Either indicate you want to include the " +
          "BuildConfig class in the final .aar or do not specify build config values.",
          originalBuildRuleParams.getBuildTarget());
    }
    if (args.includeBuildConfigClass) {
      ImmutableSortedSet<JavaLibrary> buildConfigRules =
          AndroidBinaryGraphEnhancer.addBuildConfigDeps(
              originalBuildRuleParams,
              AndroidBinary.PackageType.RELEASE,
              EnumSet.noneOf(AndroidBinary.ExopackageMode.class),
              args.buildConfigValues,
              Optional.empty(),
              resolver,
              javacOptions,
              packageableCollection);
      resolver.addAllToIndex(buildConfigRules);
      aarExtraDepsBuilder.addAll(buildConfigRules);
      classpathToIncludeInAar.addAll(
          buildConfigRules.stream()
              .map(BuildRule::getSourcePathToOutput)
              .collect(Collectors.toList()));
    }

    /* native_libraries */
    AndroidNativeLibsPackageableGraphEnhancer packageableGraphEnhancer =
        new AndroidNativeLibsPackageableGraphEnhancer(
            resolver,
            originalBuildRuleParams,
            nativePlatforms,
            ImmutableSet.of(),
            cxxBuckConfig,
            /* nativeLibraryMergeMap */ Optional.empty(),
            /* nativeLibraryMergeGlue */ Optional.empty(),
            AndroidBinary.RelinkerMode.DISABLED,
            apkModuleGraph);
    Optional<ImmutableMap<APKModule, CopyNativeLibraries>> nativeLibrariesOptional =
        packageableGraphEnhancer.enhance(packageableCollection).getCopyNativeLibraries();
    if (nativeLibrariesOptional.isPresent() &&
        nativeLibrariesOptional.get().containsKey(apkModuleGraph.getRootAPKModule())) {
      aarExtraDepsBuilder.add(
          resolver.addToIndex(
              nativeLibrariesOptional.get().get(apkModuleGraph.getRootAPKModule())));
    }

    Optional<Path> assembledNativeLibsDir = nativeLibrariesOptional.map(input -> {
      // there will be only one value for the root module
      CopyNativeLibraries copyNativeLibraries = input.get(apkModuleGraph.getRootAPKModule());
      if (copyNativeLibraries == null) {
        throw new HumanReadableException(
            "Native libraries are present but not in the root application module.");
      }
      return copyNativeLibraries.getPathToNativeLibsDir();
    });
    BuildRuleParams androidAarParams = originalBuildRuleParams.copyReplacingExtraDeps(
        Suppliers.ofInstance(ImmutableSortedSet.copyOf(aarExtraDepsBuilder.build())));
    return new AndroidAar(
        androidAarParams,
        manifest,
        androidResource,
        assembleResourceDirectories.getSourcePathToOutput(),
        assembleAssetsDirectories.getSourcePathToOutput(),
        assembledNativeLibsDir,
        ImmutableSet.copyOf(packageableCollection.getNativeLibAssetsDirectories().values()),
        classpathToIncludeInAar.build());
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AndroidLibraryDescription.Arg {
    public SourcePath manifestSkeleton;
    public BuildConfigFields buildConfigValues = BuildConfigFields.empty();
    public Boolean includeBuildConfigClass = false;
  }
}
