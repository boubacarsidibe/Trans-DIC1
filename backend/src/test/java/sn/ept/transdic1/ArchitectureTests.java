package sn.ept.transdic1;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Règles d'architecture exécutées à chaque build. Elles sont expliquées dans
 * docs/architecture/archunit.md : toute violation fait échouer la CI.
 */
@AnalyzeClasses(
    packages = "sn.ept.transdic1",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTests {

  private static final String ROOT = "sn.ept.transdic1";

  // --- Couches ---------------------------------------------------------------

  @ArchTest
  static final ArchRule layers =
      layeredArchitecture()
          .consideringOnlyDependenciesInLayers()
          .layer("Controller").definedBy("..controller..")
          .layer("Service").definedBy("..service..")
          .layer("Repository").definedBy("..repository..")
          .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
          .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller", "Service")
          .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service")
          .because("controller -> service -> repository : un contrôleur n'accède jamais à la base");

  @ArchTest
  static final ArchRule domainIsIndependentOfUpperLayers =
      noClasses()
          .that().resideInAPackage("..domain..")
          .should().dependOnClassesThat()
          .resideInAnyPackage("..controller..", "..service..", "..repository..", "..dto..")
          .because("le domaine ne connaît ni l'API, ni les services, ni la persistance");

  // --- Modules ---------------------------------------------------------------

  @ArchTest
  static final ArchRule modulesHaveNoCycles =
      slices()
          .matching(ROOT + ".(*)..")
          .should().beFreeOfCycles()
          .because("les modules inventory, monitoring, security et common doivent rester découplés");

  @ArchTest
  static final ArchRule inventoryDoesNotDependOnOtherModules =
      noClasses()
          .that().resideInAPackage(ROOT + ".inventory..")
          .should().dependOnClassesThat()
          .resideInAnyPackage(ROOT + ".monitoring..", ROOT + ".security..");

  @ArchTest
  static final ArchRule securityDoesNotDependOnBusinessModules =
      noClasses()
          .that().resideInAPackage(ROOT + ".security..")
          .should().dependOnClassesThat()
          .resideInAnyPackage(ROOT + ".inventory..", ROOT + ".monitoring..");

  @ArchTest
  static final ArchRule commonDoesNotDependOnModules =
      noClasses()
          .that().resideInAPackage(ROOT + ".common..")
          .should().dependOnClassesThat()
          .resideInAnyPackage(
              ROOT + ".inventory..", ROOT + ".monitoring..", ROOT + ".security..");

  @ArchTest
  static final ArchRule monitoringOnlyReadsInventoryDomain =
      noClasses()
          .that().resideInAPackage(ROOT + ".monitoring..")
          .should().dependOnClassesThat()
          .resideInAnyPackage(
              ROOT + ".inventory.controller..",
              ROOT + ".inventory.service..",
              ROOT + ".inventory.repository..",
              ROOT + ".inventory.dto..")
          .because("monitoring ne consomme de l'inventaire que ses entités");

  // --- API : pas d'entité exposée --------------------------------------------

  @ArchTest
  static final ArchRule controllersDoNotExposeEntities =
      methods()
          .that().areDeclaredInClassesThat().resideInAPackage("..controller..")
          .and().arePublic()
          .should(notExposeDomainTypes())
          .because("l'API retourne et reçoit des DTO, jamais des entités JPA");

  private static ArchCondition<JavaMethod> notExposeDomainTypes() {
    return new ArchCondition<>("ne pas exposer de type du domaine dans la signature") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        var involved = new java.util.ArrayList<JavaClass>(method.getReturnType().getAllInvolvedRawTypes());
        method.getParameterTypes().forEach(t -> involved.addAll(t.getAllInvolvedRawTypes()));
        involved.stream()
            .filter(c -> c.getPackageName().startsWith(ROOT) && c.getPackageName().contains(".domain"))
            .filter(c -> !c.isEnum())
            .forEach(
                c ->
                    events.add(
                        SimpleConditionEvent.violated(
                            method,
                            method.getFullName() + " expose l'entité " + c.getName())));
      }
    };
  }

  // --- Conventions de placement ----------------------------------------------

  @ArchTest
  static final ArchRule controllersLiveInControllerPackages =
      classes()
          .that().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
          .should().resideInAPackage("..controller..")
          .andShould().haveSimpleNameEndingWith("Controller");

  @ArchTest
  static final ArchRule servicesLiveInServicePackages =
      classes()
          .that().areAnnotatedWith(org.springframework.stereotype.Service.class)
          .should().resideInAPackage("..service..")
          .andShould().haveSimpleNameEndingWith("Service");

  @ArchTest
  static final ArchRule repositoriesLiveInRepositoryPackages =
      classes()
          .that().areAssignableTo(org.springframework.data.repository.Repository.class)
          .should().resideInAPackage("..repository..")
          .andShould().haveSimpleNameEndingWith("Repository");

  @ArchTest
  static final ArchRule noFieldInjection =
      noFields()
          .should().beAnnotatedWith(org.springframework.beans.factory.annotation.Autowired.class)
          .because("injection par constructeur uniquement : dépendances explicites et testables");
}
