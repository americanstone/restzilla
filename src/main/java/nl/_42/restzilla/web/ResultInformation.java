package nl._42.restzilla.web;

/**
 * Information about the REST result.
 *
 * @author Jeroen van Schagen
 * @since Dec 10, 2015
 */
public class ResultInformation {

    private final Class<?> queryType;

    private final Class<?> resultType;

    ResultInformation(Class<?> queryType, Class<?> resultType) {
        this.queryType = queryType;
        this.resultType = resultType;
    }

    public Class<?> getQueryType() {
        return queryType;
    }

    public Class<?> getResultType() {
        return resultType;
    }

}
