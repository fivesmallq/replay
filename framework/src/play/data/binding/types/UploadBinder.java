package play.data.binding.types;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.data.Upload;
import play.data.binding.Binder;
import play.data.binding.TypeBinder;
import play.db.Model;
import play.exceptions.UnexpectedException;
import play.mvc.Http.Request;
import play.mvc.Scope.Params;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;

public class UploadBinder implements TypeBinder<Model.BinaryField> {
    private static final Logger logger = LoggerFactory.getLogger(UploadBinder.class);

    @Override
    @SuppressWarnings("unchecked")
    public Object bind(String name, Annotation[] annotations, String value, Class actualClass, Type genericType) {
        if (value == null || value.trim().length() == 0) {
            return null;
        }
        try {
            Request req = Request.current();
            if (req != null && req.args != null) {
                List<Upload> uploads = (List<Upload>) req.args.get("__UPLOADS");
                if (uploads != null) {
                    for (Upload upload : uploads) {
                        if (upload.getFieldName().equals(value) && upload.getFileName().trim().length() > 0) {
                            return upload;
                        }
                    }
                }
            }

            if (Params.current() != null && Params.current().get(value + "_delete_") != null) {
                return null;
            }
            return Binder.MISSING;
        } catch (Exception e) {
            logger.error("Failed to bind upload {}", name, e);
            throw new UnexpectedException(e);
        }
    }
}
