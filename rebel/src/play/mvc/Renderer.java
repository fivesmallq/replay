package play.mvc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import play.data.validation.Validation;
import play.mvc.results.RenderJson;
import play.mvc.results.RenderTemplate;
import play.mvc.results.RenderText;
import play.mvc.results.RenderXml;
import play.templates.Template;
import play.templates.TemplateLoader;

import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;

import static java.util.Collections.emptyMap;

/**
 * @deprecated Please RETURN (not throw) `play.mvc.results.Result`
 */
@Deprecated
@Singleton
public class Renderer {
  TemplateNameResolver templateNameResolver = new TemplateNameResolver();

  private static final Gson gsonWithTimeFormat = new GsonBuilder().setDateFormat("yyyy-MM-dd'T'HH:mm:ss").create();

  public void template() {
    renderTemplate(templateNameResolver.resolveTemplateName(), emptyMap());
  }

  public void template(String templateName) {
    renderTemplate(templateName, emptyMap());
  }

  public void template(String templateName, Map<String, Object> args) {
    renderTemplate(templateName, args);
  }

  /**
   * @deprecated Please RETURN (not throw) `play.mvc.results.Result`
   */
  @Deprecated
  public Builder with(String name, Object value) {
    return new Builder(name, value);
  }

  public void text(String text) {
    throw new RenderText(text == null ? "" : text);
  }

  public void xml(Object xml) {
    throw new RenderXml(xml);
  }

  public void json(Object arg) {
    if (arg instanceof String) {
      throw new RenderJson((String) arg);
    }
    else {
      throw new RenderJson(arg, gsonWithTimeFormat);
    }
  }

  public String templateAsString(String templateName) {
    Scope.RenderArgs templateBinding = Scope.RenderArgs.current();
    templateBinding.data.putAll(Scope.RenderArgs.current().data);
    templateBinding.put("session", Scope.Session.current());
    templateBinding.put("request", Http.Request.current());
    templateBinding.put("flash", Scope.Flash.current());
    templateBinding.put("params", Scope.Params.current());
    templateBinding.put("errors", Validation.errors());
    Template t = TemplateLoader.load(templateName);
    return t.render(templateBinding.data);
  }

  public class Builder {
    private final Map<String, Object> arguments = new HashMap<>();

    private Builder(String name, Object value) {
      with(name, value);
    }

    public final Builder with(String name, Object value) {
      arguments.put(name, value);
      return this;
    }

    /**
     * @deprecated Please RETURN (not throw) `play.mvc.results.Result`
     */
    @Deprecated
    public void template() {
      renderTemplate(templateNameResolver.resolveTemplateName(), arguments);
    }

    /**
     * @deprecated Please RETURN (not throw) `play.mvc.results.Result`
     */
    @Deprecated
    public void template(String templateName) {
      renderTemplate(templateName, arguments);
    }
    
    public void json() {
      throw new RenderJson(gsonWithTimeFormat.toJson(arguments));
    }

    public void json(Gson customGson) {
      throw new RenderJson(customGson.toJson(arguments));
    }
  }

  public void renderTemplate(String templateName, Map<String, Object> args) {
    // Template datas
    Scope.RenderArgs templateBinding = Scope.RenderArgs.current();
    templateBinding.data.putAll(args);
    templateBinding.put("session", Scope.Session.current());
    templateBinding.put("request", Http.Request.current());
    templateBinding.put("flash", Scope.Flash.current());
    templateBinding.put("params", Scope.Params.current());
    templateBinding.put("errors", Validation.errors());
    Template template = TemplateLoader.load(templateNameResolver.resolveTemplateName(templateName));
    throw new RenderTemplate(template, templateBinding.data);
  }
}
