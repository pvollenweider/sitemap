import gql from 'graphql-tag';

const GetNodeMixin = gql`
    query findMixin($pathOrId: String!, $mixinsFilter: InputFieldFiltersInput) {
        jcr {
            nodeByPath(path: $pathOrId) {
                id: uuid
                mixinTypes(fieldFilter: $mixinsFilter) {
                    name
                }
            }
        }
    }
`;

const GetProperties = gql`
    query findPropertiesByPath($pathOrId: String!, $propertyNames: [String!]!) {
        jcr {
            nodeByPath(path: $pathOrId) {
                id: uuid
                properties(names: $propertyNames) {
                    name
                    value
                }
            }
        }
    }
`;

const GetNodeSitemapInfo = gql`
    query findNodeSitemapInfo($pathOrId: String!, $mixinsFilter: InputFieldFiltersInput, $propertyNames: [String!]!) {
        jcr {
            nodeByPath(path: $pathOrId) {
                id: uuid
                mixinTypes(fieldFilter: $mixinsFilter) {
                    name
                }
                properties(names: $propertyNames) {
                    name
                    value
                }
            }
        }
    }
`;

export {GetNodeMixin, GetProperties, GetNodeSitemapInfo};